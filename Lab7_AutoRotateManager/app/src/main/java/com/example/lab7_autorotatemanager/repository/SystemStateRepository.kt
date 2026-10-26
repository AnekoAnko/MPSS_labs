package com.example.lab7_autorotatemanager.repository

import com.example.lab7_autorotatemanager.ai.ActivityClass
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Actuators controllable from the dashboard and scenarios. */
enum class ActuatorType(val label: String) {
    FLASHLIGHT("Прожектор"),
    SIREN("Сирена"),
    LAMP("Лампа (реле)"),
    VIBRATION("Вібрація"),
    HUB_RELAY("Реле BLE-хаба")
}

/** Who requested an actuator change. */
enum class RequestSource(val label: String) {
    DASHBOARD("Dashboard"),
    SCENARIO("Smart-сценарій")
}

/**
 * Actuator change request. Dashboard and scenarios don't touch hardware; they post a request
 * to the shared bus and the owner executes it (ControlViewModel or BleViewModel for the hub relay).
 */
data class ActuatorRequest(
    val type: ActuatorType,
    val on: Boolean,
    val source: RequestSource,
    /** Reason shown in the log, e.g. the rule text. */
    val reason: String = ""
)

/** Actual actuator state (published by ControlViewModel). */
data class ActuatorSnapshot(
    val flashlight: Boolean = false,
    val siren: Boolean = false,
    val lamp: Boolean = false,
    val hubRelay: Boolean = false
)

/** Cloud status for the dashboard (published by CloudViewModel). */
data class CloudLink(
    val configured: Boolean = true,
    val online: Boolean = true,
    val status: String = "—",
    val pending: Int = 0,
    val lastSync: String? = null
)

/** BLE status for the dashboard (published by BleViewModel). */
data class BleLink(
    val state: String = "Disconnected",
    val linkUp: Boolean = false,
    val device: String? = null,
    val lastStatus: String? = null,
    val lastCommand: String? = null
)

/** Latest prediction for the dashboard (published by AiViewModel). */
data class AiSnapshot(
    val enabled: Boolean = true,
    val stable: ActivityClass = ActivityClass.STILL,
    val current: ActivityClass? = null,
    val confidence: Float = 0f
)

/**
 * Shared state between screens. ViewModels never call each other:
 * one writes a value here, others observe it as a Flow. Created once in AppContainer.
 */
class SystemStateRepository {

    private val _orientation = MutableStateFlow("PORTRAIT")
    /** Device orientation (PORTRAIT, LANDSCAPE_LEFT, …). */
    val orientation: StateFlow<String> = _orientation.asStateFlow()

    private val _threshold = MutableStateFlow(DEFAULT_THRESHOLD)
    /** Current DecisionEngine threshold, degrees. */
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _alarmActive = MutableStateFlow(false)
    /** Critical state: tilt alarm or anomaly. */
    val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()

    private val _aiActivity = MutableStateFlow(ActivityClass.STILL)
    /** Confirmed activity from the ML model. */
    val aiActivity: StateFlow<ActivityClass> = _aiActivity.asStateFlow()

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
    /** Actuator request bus (no replay, so old requests aren't repeated after rotation). */
    val requests: SharedFlow<ActuatorRequest> = _requests.asSharedFlow()

    fun setOrientation(value: String) { _orientation.value = value }
    fun setThreshold(value: Float) { _threshold.value = value }
    fun setAlarmActive(value: Boolean) { _alarmActive.value = value }
    fun setAiActivity(value: ActivityClass) { _aiActivity.value = value }
    fun setAi(value: AiSnapshot) { _ai.value = value }
    fun updateActuators(change: (ActuatorSnapshot) -> ActuatorSnapshot) { _actuators.value = change(_actuators.value) }
    fun setCloud(value: CloudLink) { _cloud.value = value }
    fun setBle(value: BleLink) { _ble.value = value }

    /** Returns false if the bus buffer is full and the request was dropped. */
    fun request(request: ActuatorRequest): Boolean = _requests.tryEmit(request)

    companion object {
        const val DEFAULT_THRESHOLD = 60f
    }
}
