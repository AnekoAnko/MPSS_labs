package com.example.lab6_autorotatemanager.control

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab6_autorotatemanager.AppGraph
import com.example.lab6_autorotatemanager.ai.ActivityClass
import com.example.lab6_autorotatemanager.helpers.ControlCommand
import com.example.lab6_autorotatemanager.helpers.IActuator
import com.example.lab6_autorotatemanager.helpers.RemoteCommandSource
import com.example.lab6_autorotatemanager.helpers.RemoteConnection
import com.example.lab6_autorotatemanager.helpers.RemoteMode
import com.example.lab6_autorotatemanager.helpers.RemoteSourceFactory
import com.example.lab6_autorotatemanager.helpers.SensorHub
import com.example.lab6_autorotatemanager.helpers.SmartActuator
import com.example.lab6_autorotatemanager.repository.ActuatorReport
import com.example.lab6_autorotatemanager.repository.SensorRepository
import com.example.lab6_autorotatemanager.repository.SystemStateRepository
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
 * ЛР №4: ViewModel керування виконавчими пристроями. Три джерела команд:
 *  1) AUTO   — DecisionEngine за даними акселерометра (SensorHub, ЛР №1);
 *  2) MANUAL — Action з панелі «Manual Control»;
 *  3) REMOTE — команди з Firebase (помічник RemoteCommandSource, гілка control/);
 *  4) AI     — прогноз TFLite-моделі «Аномалія» (ЛР №6, через SystemStateRepository).
 * Діє правило «остання команда перемагає»; джерело останньої зміни показується в UI.
 * Залізо — лише через помічник IActuator, хмара — лише через SensorRepository.
 */
class ControlViewModel(
    private val actuator: IActuator = SmartActuator(AppGraph.app),
    private val remote: RemoteCommandSource? = RemoteSourceFactory.create(AppGraph.app),
    private val repository: SensorRepository = AppGraph.sensorRepository,
    private val hub: SensorHub = AppGraph.sensorHub,
    private val system: SystemStateRepository = AppGraph.systemState,
    private val deviceId: String = AppGraph.deviceId,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val engine = DecisionEngine()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Попередня команда з хмари — застосовуємо лише поля, що змінилися. */
    private var lastRemote: ControlCommand? = null
    private var reportJob: Job? = null
    private var lastSource = ControlSource.SYSTEM

    /** Чи підписані ми зараз на команди з хмари. */
    private var remoteRunning = false

    /** ЛР №6: відкладене вимкнення тривоги ШІ. */
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

        // Ліхтарик вимкнули/увімкнули із шторки — синхронізуємо State (зворотний зв'язок від системи)
        actuator.onTorchChanged = { enabled ->
            if (enabled != _state.value.flashlight.on) {
                _state.update { it.copy(flashlight = it.flashlight.copy(on = enabled, source = ControlSource.SYSTEM, changedAt = now())) }
                log("Ліхтарик ${onOff(enabled)} системою (шторка)")
            }
        }

        // А. Локальне автоматичне керування: дані акселерометра після фільтра ЛР №1
        viewModelScope.launch {
            hub.filtered.collect { s -> onSensorSample(s.x, s.y, s.z) }
        }

        // ЛР №6: підтверджений прогноз моделі
        viewModelScope.launch {
            system.aiActivity.drop(1).collect { activity -> onAiActivity(activity == ActivityClass.ANOMALY) }
        }

        // Б. Дистанційне керування (Cloud-to-Device): підписка — за ControlAction.Start
        if (remote == null) log("FIREBASE_DB_URL не задано — дистанційне керування вимкнено")
    }

    // ЄДИНИЙ вхід для дій користувача
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

    // ------------------------------------------------------------ А. Локальне автоматичне керування

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

    // ------------------------------------------------------------ ЛР №6: реакція на прогноз ML-моделі

    /**
     * ANOMALY → вмикаємо сирену і прожектор (джерело AI);
     * інший клас → вимикаємо їх, але не раніше ніж через [AI_ALARM_HOLD_MS]
     * (короткий удар не повинен дати «пік» сирени тривалістю 1 с).
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
        // вимикаємо лише те, що ввімкнула саме модель (ручні/хмарні команди не чіпаємо)
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
            // вимикаємо лише те, що ввімкнула автоматика
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

    /** Спільний стан тривоги для інших екранів. */
    private fun publishAlarm() {
        system.setAlarmActive(_state.value.let { it.alarmActive || it.aiAlarm })   // ЛР №6: + тривога ШІ
    }

    // ------------------------------------------------------------ Б. Дистанційне керування

    /** Activity видно → слухаємо гілку control/ у Firebase. */
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

    /** Додаток згорнуто → знімаємо слухача, щоб не тримати з'єднання у фоні. */
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

        // Застосовуємо лише ті поля, які з'явилися або змінилися (edge-triggered)
        command.ledStatus?.takeIf { previous == null || it != previous.ledStatus }
            ?.let { setLamp(it, ControlSource.REMOTE) }
        command.flashlight?.takeIf { previous == null || it != previous.flashlight }
            ?.let { setFlashlight(it, ControlSource.REMOTE) }
        command.siren?.takeIf { previous == null || it != previous.siren }
            ?.let { setSiren(it, ControlSource.REMOTE) }
        command.threshold?.takeIf { previous == null || it != previous.threshold }
            ?.let { setThreshold(it, ControlSource.REMOTE) }
    }

    // ------------------------------------------------------------ Виконання команд

    private fun setFlashlight(on: Boolean, source: ControlSource) {
        if (_state.value.flashlight.on == on) return
        // Спершу оновлюємо стан, щоб TorchCallback не сприйняв нашу ж команду як зовнішню
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
        if (source == ControlSource.REMOTE) _events.trySend(ControlEvent.ShowMessage("Команда з хмари: $what"))
        scheduleReport()
    }

    /** Зворотний зв'язок: стан актуаторів → status/{deviceId}; кілька змін поспіль — один запит. */
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
                lastSource = lastSource.name
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
