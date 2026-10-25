package com.example.lab7_autorotatemanager.repository

import com.example.lab7_autorotatemanager.ai.ActivityClass
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** ЛР №7: які виконавчі пристрої можна вмикати з Dashboard і Smart-сценаріїв. */
enum class ActuatorType(val label: String) {
    FLASHLIGHT("Прожектор"),
    SIREN("Сирена"),
    LAMP("Лампа (реле)"),
    VIBRATION("Вібрація"),
    HUB_RELAY("Реле BLE-хаба")
}

/** Хто попросив змінити актуатор (для журналу і джерела в State «Керування»). */
enum class RequestSource(val label: String) {
    DASHBOARD("Dashboard"),
    SCENARIO("Smart-сценарій")
}

/**
 * ЛР №7: запит на зміну актуатора. Dashboard і ScenarioViewModel не керують залізом самі:
 * вони кладуть запит у спільну «шину», а виконує його той, хто володіє пристроєм
 * (ControlViewModel — прожектор/сирена/лампа/вібрація, BleViewModel — реле хаба).
 */
data class ActuatorRequest(
    val type: ActuatorType,
    val on: Boolean,
    val source: RequestSource,
    /** Пояснення для журналу, наприклад «X > 10 м/с²». */
    val reason: String = ""
)

/** Фактичний стан актуаторів (публікує ControlViewModel). */
data class ActuatorSnapshot(
    val flashlight: Boolean = false,
    val siren: Boolean = false,
    val lamp: Boolean = false,
    val hubRelay: Boolean = false
)

/** Статус хмари для Dashboard (публікує CloudViewModel). */
data class CloudLink(
    val configured: Boolean = true,
    val online: Boolean = true,
    val status: String = "—",
    val pending: Int = 0,
    val lastSync: String? = null
)

/** Статус Bluetooth-мока для Dashboard (публікує BleViewModel). */
data class BleLink(
    val state: String = "Disconnected",
    val linkUp: Boolean = false,
    val device: String? = null,
    val lastStatus: String? = null,
    val lastCommand: String? = null
)

/** Останній прогноз моделі для Dashboard (публікує AiViewModel). */
data class AiSnapshot(
    val enabled: Boolean = true,
    val stable: ActivityClass = ActivityClass.STILL,
    val current: ActivityClass? = null,
    val confidence: Float = 0f
)

/**
 * Спільний стан системи, який потрібен кільком екранам.
 * ViewModel не звертаються одна до одної: одна записує значення сюди,
 * інші отримують його як Flow.
 *
 *   MonitorViewModel  → orientation  → CloudViewModel, BleViewModel (ORIENT:…)
 *   AiViewModel       → aiActivity, ai → ControlViewModel (Аномалія → актуатори), AnalyticsViewModel (Біг), Dashboard
 *   ControlViewModel  → threshold, alarmActive, actuators → AnalyticsViewModel, BleViewModel (ALARM:…), Dashboard
 *   CloudViewModel    → cloud → Dashboard;   BleViewModel → ble → Dashboard
 *   Dashboard, ScenarioViewModel → requests → ControlViewModel, BleViewModel
 *
 * Створюється один раз у AppContainer (Manual DI).
 */
class SystemStateRepository {

    private val _orientation = MutableStateFlow("PORTRAIT")
    /** Стан Auto-Rotate Manager з ЛР №1 (PORTRAIT, LANDSCAPE_LEFT, …). */
    val orientation: StateFlow<String> = _orientation.asStateFlow()

    private val _threshold = MutableStateFlow(DEFAULT_THRESHOLD)
    /** Поточний поріг DecisionEngine (ЛР №4), градуси. */
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _alarmActive = MutableStateFlow(false)
    /** Критичний стан: увімкнена система безпеки (нахил ЛР №4 або «Аномалія» ЛР №6). */
    val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()

    private val _aiActivity = MutableStateFlow(ActivityClass.STILL)
    /** ЛР №6: підтверджена активність за прогнозом TFLite-моделі. */
    val aiActivity: StateFlow<ActivityClass> = _aiActivity.asStateFlow()

    // ------------------------------------------------------------ ЛР №7

    private val _ai = MutableStateFlow(AiSnapshot())
    val ai: StateFlow<AiSnapshot> = _ai.asStateFlow()

    private val _actuators = MutableStateFlow(ActuatorSnapshot())
    val actuators: StateFlow<ActuatorSnapshot> = _actuators.asStateFlow()

    private val _cloud = MutableStateFlow(CloudLink())
    val cloud: StateFlow<CloudLink> = _cloud.asStateFlow()

    private val _ble = MutableStateFlow(BleLink())
    val ble: StateFlow<BleLink> = _ble.asStateFlow()

    private val _requests = MutableSharedFlow<ActuatorRequest>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Шина запитів до актуаторів (без replay: старий запит після повороту екрана не повториться). */
    val requests: SharedFlow<ActuatorRequest> = _requests.asSharedFlow()

    fun setOrientation(value: String) { _orientation.value = value }
    fun setThreshold(value: Float) { _threshold.value = value }
    fun setAlarmActive(value: Boolean) { _alarmActive.value = value }
    fun setAiActivity(value: ActivityClass) { _aiActivity.value = value }
    fun setAi(value: AiSnapshot) { _ai.value = value }
    fun setActuators(value: ActuatorSnapshot) { _actuators.value = value }
    fun updateActuators(change: (ActuatorSnapshot) -> ActuatorSnapshot) { _actuators.value = change(_actuators.value) }
    fun setCloud(value: CloudLink) { _cloud.value = value }
    fun setBle(value: BleLink) { _ble.value = value }

    /** Повертає false, якщо буфер шини переповнений (запит відкинуто). */
    fun request(request: ActuatorRequest): Boolean = _requests.tryEmit(request)

    companion object {
        const val DEFAULT_THRESHOLD = 60f
    }
}
