package com.example.lab6_autorotatemanager.control

import com.example.lab6_autorotatemanager.helpers.RemoteConnection
import com.example.lab6_autorotatemanager.helpers.RemoteMode

/** Хто останнім змінив стан виконавчого пристрою (актуатора). */
enum class ControlSource(val label: String) {
    AUTO("авто · DecisionEngine"),
    MANUAL("вручну · Manual Control"),
    REMOTE("хмара · Firebase"),
    AI("ШІ · ActivityClassifier"),     // ЛР №6
    SYSTEM("система")
}

/**
 * Стан одного виконавчого пристрою.
 * hardware = true — керуємо реальним залізом смартфона,
 * hardware = false — апаратної можливості немає, стан лише імітується в UI.
 */
data class ActuatorStatus(
    val on: Boolean = false,
    val source: ControlSource = ControlSource.SYSTEM,
    val changedAt: String? = null,
    val hardware: Boolean = true
)

// State — усе, що видно на вкладці «Керування» (ЛР №4).
data class ControlState(
    // DecisionEngine
    val autoEnabled: Boolean = true,
    val threshold: Float = DecisionEngine.DEFAULT_THRESHOLD,
    val tilt: Float = 0f,
    val alarmActive: Boolean = false,
    /** ЛР №6: актуатори ввімкнено за прогнозом ML-моделі («Аномалія»). */
    val aiAlarm: Boolean = false,
    // Виконавчі пристрої
    val flashlight: ActuatorStatus = ActuatorStatus(),
    val siren: ActuatorStatus = ActuatorStatus(),
    val lamp: ActuatorStatus = ActuatorStatus(hardware = false),
    val hasFlash: Boolean = false,
    val hasVibrator: Boolean = false,
    // Дистанційне керування
    val remoteMode: RemoteMode = RemoteMode.NONE,
    val remoteConnection: RemoteConnection = RemoteConnection.DISCONNECTED,
    val remoteDetails: String? = null,
    val lastCommandRaw: String? = null,
    val lastCommandTime: String? = null,
    val feedbackStatus: String? = null,
    val log: List<String> = emptyList()
)

// Action — дії користувача: автоматика і панель «Manual Control».
sealed interface ControlAction {
    data class SetAutoEnabled(val enabled: Boolean) : ControlAction
    data class SetThreshold(val value: Float) : ControlAction
    data class SetFlashlight(val on: Boolean) : ControlAction
    data class SetSiren(val on: Boolean) : ControlAction
    data class SetLamp(val on: Boolean) : ControlAction
    /** Аварійна кнопка «Вимкнути все». */
    data object AllOff : ControlAction
}

// Event — разові повідомлення.
sealed interface ControlEvent {
    data class ShowMessage(val text: String) : ControlEvent
}
