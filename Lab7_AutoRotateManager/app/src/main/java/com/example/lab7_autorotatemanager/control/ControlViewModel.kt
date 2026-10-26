package com.example.lab7_autorotatemanager.control

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab7_autorotatemanager.ai.ActivityClass
import com.example.lab7_autorotatemanager.helpers.ControlCommand
import com.example.lab7_autorotatemanager.helpers.IActuator
import com.example.lab7_autorotatemanager.helpers.RemoteCommandSource
import com.example.lab7_autorotatemanager.helpers.RemoteConnection
import com.example.lab7_autorotatemanager.helpers.RemoteMode
import com.example.lab7_autorotatemanager.helpers.SensorHub
import com.example.lab7_autorotatemanager.repository.ActuatorReport
import com.example.lab7_autorotatemanager.repository.ActuatorRequest
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.RequestSource
import com.example.lab7_autorotatemanager.repository.SensorRepository
import com.example.lab7_autorotatemanager.repository.SystemStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controls the actuators. Command sources: AUTO (DecisionEngine), MANUAL (UI), REMOTE (Firebase),
 * AI (anomaly), DASHBOARD / SCENARIO (request bus). The last command wins.
 * Hardware goes through IActuator, the cloud through SensorRepository.
 */
class ControlViewModel(
    private val actuator: IActuator,
    private val remote: RemoteCommandSource?,
    private val repository: SensorRepository,
    private val hub: SensorHub,
    private val system: SystemStateRepository,
    private val deviceId: String,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val engine = DecisionEngine()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Previous cloud command: only changed fields are applied. */
    private var lastRemote: ControlCommand? = null
    private var reportJob: Job? = null
    private var lastSource = ControlSource.SYSTEM

    /** Whether we are subscribed to cloud commands. */
    private var remoteRunning = false

    /** Delayed release of the AI alarm. */
    private var aiReleaseJob: Job? = null

    private val _state = MutableStateFlow(
        ControlState(
            threshold = engine.threshold,
            hasFlash = actuator.hasFlash,
            hasVibrator = actuator.hasVibrator,
            flashlight = ActuatorStatus(hardware = actuator.hasFlash),
            siren = ActuatorStatus(hardware = actuator.hasVibrator),
            remoteMode = remote?.mode ?: RemoteMode.NONE
        )
    )
    val state: StateFlow<ControlState> = _state.asStateFlow()

    private val _events = Channel<ControlEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        system.setThreshold(engine.threshold)
        if (!actuator.hasFlash) log("FEATURE_CAMERA_FLASH відсутній → прожектор у режимі імітації")
        if (!actuator.hasVibrator) log("Вібромотор відсутній → сирена лише звукова")

        // Torch toggled outside the app: sync the state
        actuator.onTorchChanged = { enabled ->
            if (enabled != _state.value.flashlight.on) {
                _state.update { it.copy(flashlight = it.flashlight.copy(on = enabled, source = ControlSource.SYSTEM, changedAt = now())) }
                log("Ліхтарик ${onOff(enabled)} системою (шторка)")
                publishActuators()
            }
        }

        // Requests from dashboard and scenarios (hub relay is handled by BleViewModel)
        viewModelScope.launch {
            system.requests.collect { request -> onRequest(request) }
        }

        // Automatic control from filtered accelerometer data
        viewModelScope.launch {
            hub.filtered.collect { s -> onSensorSample(s.x, s.y, s.z) }
        }

        // Confirmed model prediction
        viewModelScope.launch {
            system.aiActivity.drop(1).collect { activity -> onAiActivity(activity == ActivityClass.ANOMALY) }
        }

        // Cloud commands: subscription starts on ControlAction.Start
        if (remote == null) log("FIREBASE_DB_URL не задано — дистанційне керування вимкнено")
    }

    // Single entry point for UI actions
    fun onAction(action: ControlAction) {
        when (action) {
            ControlAction.Start -> startRemote()
            ControlAction.Stop -> stopRemote()
            is ControlAction.SetAutoEnabled -> setAutoEnabled(action.enabled)
            is ControlAction.SetThreshold -> setThreshold(action.value, ControlSource.MANUAL)
            is ControlAction.SetFlashlight -> setFlashlight(action.on, ControlSource.MANUAL)
            is ControlAction.SetSiren -> setSiren(action.on, ControlSource.MANUAL)
            is ControlAction.SetLamp -> setLamp(action.on, ControlSource.MANUAL)
            ControlAction.AllOff -> {
                setSiren(false, ControlSource.MANUAL)
                setFlashlight(false, ControlSource.MANUAL)
                setLamp(false, ControlSource.MANUAL)
            }
        }
    }

    private fun onSensorSample(x: Float, y: Float, z: Float) {
        if (!_state.value.autoEnabled) {
            _state.update { it.copy(tilt = DecisionEngine.tiltDegrees(x, y, z)) }
            return
        }
        val result = engine.evaluate(x, y, z, clock())
        _state.update { it.copy(tilt = result.tiltDeg, alarmActive = result.alarmActive) }

        when (result.decision) {
            Decision.ACTIVATE -> {
                log("ТРИВОГА: нахил ${"%.0f".format(result.tiltDeg)}° > поріг ${"%.0f".format(engine.threshold)}°")
                setSiren(true, ControlSource.AUTO)
                setFlashlight(true, ControlSource.AUTO)
            }
            Decision.DEACTIVATE -> {
                log("Норма: нахил ${"%.0f".format(result.tiltDeg)}° < ${"%.0f".format(engine.threshold - engine.hysteresis)}°")
                setSiren(false, ControlSource.AUTO)
                setFlashlight(false, ControlSource.AUTO)
            }
            Decision.NO_CHANGE -> Unit
        }
        publishAlarm()
    }

    private fun onRequest(r: ActuatorRequest) {
        val source = if (r.source == RequestSource.SCENARIO) ControlSource.SCENARIO else ControlSource.DASHBOARD
        when (r.type) {
            ActuatorType.FLASHLIGHT -> setFlashlight(r.on, source)
            ActuatorType.SIREN -> setSiren(r.on, source)
            ActuatorType.LAMP -> setLamp(r.on, source)
            ActuatorType.VIBRATION -> if (r.on) {
                actuator.vibratePulse()
                log("Вібро-імпульс · ${source.label}${if (r.reason.isNotBlank()) " · ${r.reason}" else ""}")
            }
            ActuatorType.HUB_RELAY -> Unit   // handled by BleViewModel
        }
    }

    /** Publishes the actual actuator state for the dashboard. */
    private fun publishActuators() {
        val s = _state.value
        system.updateActuators { it.copy(flashlight = s.flashlight.on, siren = s.siren.on, lamp = s.lamp.on) }
    }

    /**
     * ANOMALY turns on siren and floodlight (source AI). Other classes turn them off,
     * but not earlier than [AI_ALARM_HOLD_MS], so a short hit doesn't cause a 1 s siren blip.
     */
    private fun onAiActivity(isAnomaly: Boolean) {
        if (isAnomaly) {
            aiReleaseJob?.cancel()
            if (!_state.value.autoEnabled || _state.value.aiAlarm) return
            _state.update { it.copy(aiAlarm = true) }
            log("ШІ: АНОМАЛІЯ (вібрація/удар) → актуатори")
            setSiren(true, ControlSource.AI)
            setFlashlight(true, ControlSource.AI)
            publishAlarm()
        } else if (_state.value.aiAlarm) {
            aiReleaseJob?.cancel()
            aiReleaseJob = viewModelScope.launch {
                delay(AI_ALARM_HOLD_MS)
                releaseAiAlarm()
            }
        }
    }

    private fun releaseAiAlarm() {
        if (!_state.value.aiAlarm) return
        _state.update { it.copy(aiAlarm = false) }
        log("ШІ: аномалію знято")
        // only turn off what the model turned on
        if (_state.value.siren.source == ControlSource.AI) setSiren(false, ControlSource.AI)
        if (_state.value.flashlight.source == ControlSource.AI) setFlashlight(false, ControlSource.AI)
        publishAlarm()
    }

    private fun setAutoEnabled(enabled: Boolean) {
        _state.update { it.copy(autoEnabled = enabled) }
        log(if (enabled) "Автоматичне керування увімкнено" else "Автоматичне керування вимкнено")
        if (!enabled) {
            aiReleaseJob?.cancel()
            releaseAiAlarm()
        }
        if (!enabled && engine.alarmActive) {
            engine.reset()
            _state.update { it.copy(alarmActive = false) }
            // only turn off what automation turned on
            if (_state.value.siren.source == ControlSource.AUTO) setSiren(false, ControlSource.AUTO)
            if (_state.value.flashlight.source == ControlSource.AUTO) setFlashlight(false, ControlSource.AUTO)
        }
        publishAlarm()
    }

    private fun setThreshold(value: Float, source: ControlSource) {
        engine.threshold = value
        _state.update { it.copy(threshold = engine.threshold) }
        system.setThreshold(engine.threshold)
        if (source != ControlSource.MANUAL) log("Поріг = ${"%.0f".format(engine.threshold)}° (${source.label})")
        scheduleReport()
    }

    /** Publishes the combined alarm state for other screens. */
    private fun publishAlarm() {
        val critical = _state.value.let { it.alarmActive || it.aiAlarm }   // tilt alarm or AI alarm
        if (critical != system.alarmActive.value) {
            system.setAlarmActive(critical)   // → BLE hub, Room event, dashboard
            scheduleReport()                  // report critical state to status/{deviceId}
        }
    }

    /** App visible: listen to control/ in Firebase. */
    private fun startRemote() {
        if (remote == null || remoteRunning) return
        remoteRunning = true
        remote.start(
            onCommand = { command, raw -> viewModelScope.launch { applyRemote(command, raw) } },
            onConnection = { connection, details ->
                viewModelScope.launch {
                    val changed = connection != _state.value.remoteConnection
                    _state.update { it.copy(remoteConnection = connection, remoteDetails = details) }
                    if (changed) log("Хмара: ${connection.label}${details?.let { " ($it)" } ?: ""}")
                }
            }
        )
    }

    /** App hidden: stop listening so no connection stays open in the background. */
    private fun stopRemote() {
        if (remote == null || !remoteRunning) return
        remoteRunning = false
        remote.stop()
        _state.update { it.copy(remoteConnection = RemoteConnection.DISCONNECTED, remoteDetails = "призупинено: додаток згорнуто") }
        log("Хмара: слухача знято (додаток згорнуто)")
    }

    private fun applyRemote(command: ControlCommand, raw: String) {
        val previous = lastRemote
        lastRemote = command
        _state.update { it.copy(lastCommandRaw = raw, lastCommandTime = now()) }
        log("Команда з хмари: $raw")

        // Apply only fields that appeared or changed (edge-triggered)
        command.ledStatus?.takeIf { previous == null || it != previous.ledStatus }
            ?.let { setLamp(it, ControlSource.REMOTE) }
        command.flashlight?.takeIf { previous == null || it != previous.flashlight }
            ?.let { setFlashlight(it, ControlSource.REMOTE) }
        command.siren?.takeIf { previous == null || it != previous.siren }
            ?.let { setSiren(it, ControlSource.REMOTE) }
        command.threshold?.takeIf { previous == null || it != previous.threshold }
            ?.let { setThreshold(it, ControlSource.REMOTE) }
    }

    private fun setFlashlight(on: Boolean, source: ControlSource) {
        if (_state.value.flashlight.on == on) return
        // Update state first so TorchCallback doesn't treat our own command as external
        _state.update { it.copy(flashlight = ActuatorStatus(on, source, now(), it.flashlight.hardware)) }
        val hardware = actuator.toggleFlashlight(on)
        _state.update { it.copy(flashlight = it.flashlight.copy(hardware = hardware)) }
        log("Прожектор ${onOff(on)} · ${source.label}${if (hardware) "" else " · імітація"}")
        onChanged(source, "Прожектор ${onOff(on)}")
    }

    private fun setSiren(on: Boolean, source: ControlSource) {
        if (_state.value.siren.on == on) return
        if (on) actuator.triggerAlarm() else actuator.stopAlarm()
        _state.update { it.copy(siren = ActuatorStatus(on, source, now(), actuator.hasVibrator)) }
        log("Сирена ${onOff(on)} · ${source.label}")
        onChanged(source, "Сирена ${onOff(on)}")
    }

    private fun setLamp(on: Boolean, source: ControlSource) {
        if (_state.value.lamp.on == on) return
        _state.update { it.copy(lamp = ActuatorStatus(on, source, now(), hardware = false)) }
        log("Віртуальна лампа ${onOff(on)} · ${source.label}")
        onChanged(source, "Віртуальна лампа ${onOff(on)}")
    }

    private fun onChanged(source: ControlSource, what: String) {
        lastSource = source
        publishActuators()
        if (source == ControlSource.REMOTE) _events.trySend(ControlEvent.ShowMessage("Команда з хмари: $what"))
        scheduleReport()
    }

    /** Reports actuator state to status/{deviceId}; several changes in a row become one request. */
    private fun scheduleReport() {
        if (!repository.isCloudConfigured) return
        reportJob?.cancel()
        reportJob = viewModelScope.launch {
            delay(REPORT_DEBOUNCE_MS)
            val s = _state.value
            val report = ActuatorReport(
                flashlight = s.flashlight.on,
                flashlightHardware = s.flashlight.hardware,
                siren = s.siren.on,
                lamp = s.lamp.on,
                alarm = s.alarmActive,
                tilt = Math.round(s.tilt * 10f) / 10f,
                threshold = s.threshold,
                lastSource = lastSource.name,
                activity = system.aiActivity.value.name,
                critical = s.alarmActive || s.aiAlarm
            )
            val status = repository.reportStatus(deviceId, report).fold(
                onSuccess = { "✓ status/$deviceId оновлено о ${now()}" },
                onFailure = { "✗ ${it.message ?: it.javaClass.simpleName}" }
            )
            _state.update { it.copy(feedbackStatus = status) }
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${now()}  $message"
        _state.update { it.copy(log = (it.log + line).takeLast(LOG_SIZE)) }
    }

    private fun now(): String = timeFormat.format(Date(clock()))

    private fun onOff(on: Boolean) = if (on) "УВІМК" else "ВИМК"

    override fun onCleared() {
        remote?.stop()
        actuator.release()
    }

    companion object {
        const val TAG = "Control"
        const val LOG_SIZE = 8
        const val REPORT_DEBOUNCE_MS = 300L
        const val AI_ALARM_HOLD_MS = 5_000L
    }
}
