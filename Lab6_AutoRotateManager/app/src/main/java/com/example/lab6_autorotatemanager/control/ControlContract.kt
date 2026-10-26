package com.example.lab6_autorotatemanager.control

import com.example.lab6_autorotatemanager.helpers.RemoteConnection
import com.example.lab6_autorotatemanager.helpers.RemoteMode

/** Who changed an actuator last. */
enum class ControlSource(val label: String) {
    AUTO("авто · DecisionEngine"),
    MANUAL("вручну · Manual Control"),
    REMOTE("хмара · Firebase"),
    AI("ШІ · ActivityClassifier"),
    SYSTEM("система")
}

/** State of one actuator. hardware = false means the device lacks it and the state is only simulated. */
data class ActuatorStatus(
    val on: Boolean = false,
    val source: ControlSource = ControlSource.SYSTEM,
    val changedAt: String? = null,
    val hardware: Boolean = true
)

// State: the Control tab.
data class ControlState(
    val autoEnabled: Boolean = true,
    val threshold: Float = DecisionEngine.DEFAULT_THRESHOLD,
    val tilt: Float = 0f,
    val alarmActive: Boolean = false,
    /** Actuators turned on by the model (anomaly). */
    val aiAlarm: Boolean = false,
    val flashlight: ActuatorStatus = ActuatorStatus(),
    val siren: ActuatorStatus = ActuatorStatus(),
    val lamp: ActuatorStatus = ActuatorStatus(hardware = false),
    val hasFlash: Boolean = false,
    val hasVibrator: Boolean = false,
    val remoteMode: RemoteMode = RemoteMode.NONE,
    val remoteConnection: RemoteConnection = RemoteConnection.DISCONNECTED,
    val remoteDetails: String? = null,
    val lastCommandRaw: String? = null,
    val lastCommandTime: String? = null,
    val feedbackStatus: String? = null,
    val log: List<String> = emptyList()
)

// Action: automation and manual controls.
sealed interface ControlAction {
    /** onStart: subscribe to cloud commands. */
    data object Start : ControlAction
    /** onStop (not on rotation): unsubscribe from cloud commands. */
    data object Stop : ControlAction
    data class SetAutoEnabled(val enabled: Boolean) : ControlAction
    data class SetThreshold(val value: Float) : ControlAction
    data class SetFlashlight(val on: Boolean) : ControlAction
    data class SetSiren(val on: Boolean) : ControlAction
    data class SetLamp(val on: Boolean) : ControlAction
    /** Emergency "all off" button. */
    data object AllOff : ControlAction
}

// Event: one-off messages.
sealed interface ControlEvent {
    data class ShowMessage(val text: String) : ControlEvent
}
