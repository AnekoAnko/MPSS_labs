package com.example.lab4_autorotatemanager.control

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab4_autorotatemanager.BuildConfig
import com.example.lab4_autorotatemanager.cloud.deviceIdOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel керування виконавчими пристроями. Об'єднує три джерела команд:
 *  1) AUTO   — DecisionEngine за даними акселерометра (ЛР №1);
 *  2) MANUAL — панель «Manual Control» на екрані;
 *  3) REMOTE — команди з Firebase (гілка control/).
 * Діє правило «остання команда перемагає»; джерело останньої зміни показується в UI.
 */
class ControlViewModel @JvmOverloads constructor(
    app: Application,
    private val actuator: IActuator = SmartActuator(app),
    private val remote: RemoteCommandSource? = RemoteSourceFactory.create(app),
    private val feedbackApi: ControlFeedbackApi? = ControlModule.createFeedbackApi(BuildConfig.FIREBASE_DB_URL),
    private val authToken: String? = BuildConfig.FIREBASE_AUTH.ifBlank { null },
    private val deviceId: String = deviceIdOf(app),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AndroidViewModel(app) {

    private val engine = DecisionEngine()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Попередня команда з хмари — застосовуємо лише поля, що змінилися. */
    private var lastRemote: ControlCommand? = null
    private var reportJob: Job? = null
    private var lastSource = ControlSource.SYSTEM

    private val _uiState = MutableStateFlow(
        ControlUiState(
            threshold = engine.threshold,
            hasFlash = actuator.hasFlash,
            hasVibrator = actuator.hasVibrator,
            flashlight = ActuatorStatus(hardware = actuator.hasFlash),
            siren = ActuatorStatus(hardware = actuator.hasVibrator),
            remoteMode = remote?.mode ?: RemoteMode.NONE
        )
    )
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    init {
        if (!actuator.hasFlash) log("FEATURE_CAMERA_FLASH відсутній → прожектор у режимі імітації")
        if (!actuator.hasVibrator) log("Вібромотор відсутній → сирена лише звукова")

        // Ліхтарик вимкнули/увімкнули із шторки — синхронізуємо UI (зворотний зв'язок від системи)
        actuator.onTorchChanged = { enabled ->
            if (enabled != _uiState.value.flashlight.on) {
                _uiState.update { it.copy(flashlight = it.flashlight.copy(on = enabled, source = ControlSource.SYSTEM, changedAt = now())) }
                log("Ліхтарик ${onOff(enabled)} системою (шторка)")
            }
        }

        if (remote == null) {
            log("FIREBASE_DB_URL не задано — дистанційне керування вимкнено")
        } else {
            remote.start(
                onCommand = { command, raw -> viewModelScope.launch { applyRemote(command, raw) } },
                onConnection = { state, details ->
                    viewModelScope.launch {
                        val changed = state != _uiState.value.remoteConnection
                        _uiState.update { it.copy(remoteConnection = state, remoteDetails = details) }
                        if (changed) log("Хмара: ${state.label}${details?.let { " ($it)" } ?: ""}")
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------ А. Локальне автоматичне керування

    /** Виклик з SensorEventListener (MainActivity.onSensorChanged) після фільтра ЛР №1. */
    fun onSensorSample(x: Float, y: Float, z: Float) {
        if (!_uiState.value.autoEnabled) {
            _uiState.update { it.copy(tilt = DecisionEngine.tiltDegrees(x, y, z)) }
            return
        }
        val result = engine.evaluate(x, y, z, clock())
        _uiState.update { it.copy(tilt = result.tiltDeg, alarmActive = result.alarmActive) }

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
    }

    fun setAutoEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoEnabled = enabled) }
        log(if (enabled) "Автоматичне керування увімкнено" else "Автоматичне керування вимкнено")
        if (!enabled && engine.alarmActive) {
            engine.reset()
            _uiState.update { it.copy(alarmActive = false) }
            // вимикаємо лише те, що ввімкнула автоматика
            if (_uiState.value.siren.source == ControlSource.AUTO) setSiren(false, ControlSource.AUTO)
            if (_uiState.value.flashlight.source == ControlSource.AUTO) setFlashlight(false, ControlSource.AUTO)
        }
    }

    fun setThreshold(value: Float, source: ControlSource = ControlSource.MANUAL) {
        engine.threshold = value
        _uiState.update { it.copy(threshold = engine.threshold) }
        if (source != ControlSource.MANUAL) log("Поріг = ${"%.0f".format(engine.threshold)}° (${source.label})")
        scheduleReport()
    }

    // ------------------------------------------------------------ Manual Control

    fun manualFlashlight(on: Boolean) = setFlashlight(on, ControlSource.MANUAL)
    fun manualSiren(on: Boolean) = setSiren(on, ControlSource.MANUAL)
    fun manualLamp(on: Boolean) = setLamp(on, ControlSource.MANUAL)

    /** Аварійна кнопка: вимкнути всі виконавчі пристрої. */
    fun allOff() {
        setSiren(false, ControlSource.MANUAL)
        setFlashlight(false, ControlSource.MANUAL)
        setLamp(false, ControlSource.MANUAL)
    }

    // ------------------------------------------------------------ Б. Дистанційне керування

    private fun applyRemote(command: ControlCommand, raw: String) {
        val previous = lastRemote
        lastRemote = command
        _uiState.update { it.copy(lastCommandRaw = raw, lastCommandTime = now()) }
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
        if (_uiState.value.flashlight.on == on) return
        // Спершу оновлюємо стан, щоб TorchCallback не сприйняв нашу ж команду як зовнішню
        _uiState.update { it.copy(flashlight = ActuatorStatus(on, source, now(), it.flashlight.hardware)) }
        val hardware = actuator.toggleFlashlight(on)
        _uiState.update { it.copy(flashlight = it.flashlight.copy(hardware = hardware)) }
        log("Прожектор ${onOff(on)} · ${source.label}${if (hardware) "" else " · імітація"}")
        lastSource = source
        scheduleReport()
    }

    private fun setSiren(on: Boolean, source: ControlSource) {
        if (_uiState.value.siren.on == on) return
        if (on) actuator.triggerAlarm() else actuator.stopAlarm()
        _uiState.update { it.copy(siren = ActuatorStatus(on, source, now(), actuator.hasVibrator)) }
        log("Сирена ${onOff(on)} · ${source.label}")
        lastSource = source
        scheduleReport()
    }

    private fun setLamp(on: Boolean, source: ControlSource) {
        if (_uiState.value.lamp.on == on) return
        _uiState.update { it.copy(lamp = ActuatorStatus(on, source, now(), hardware = false)) }
        log("Віртуальна лампа ${onOff(on)} · ${source.label}")
        lastSource = source
        scheduleReport()
    }

    /** Звіт про стан актуаторів у status/{deviceId}; кілька змін поспіль об'єднуються в один запит. */
    private fun scheduleReport() {
        val api = feedbackApi ?: return
        reportJob?.cancel()
        reportJob = viewModelScope.launch {
            delay(REPORT_DEBOUNCE_MS)
            val s = _uiState.value
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
            val status = try {
                val response = withContext(Dispatchers.IO) { api.putStatus(deviceId, report, authToken) }
                if (response.isSuccessful) "✓ status/$deviceId оновлено о ${now()}"
                else "✗ HTTP ${response.code()} при записі status/"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "✗ ${e.message ?: e.javaClass.simpleName}"
            }
            _uiState.update { it.copy(feedbackStatus = status) }
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${now()}  $message"
        _uiState.update { it.copy(log = (it.log + line).takeLast(LOG_SIZE)) }
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
    }
}
