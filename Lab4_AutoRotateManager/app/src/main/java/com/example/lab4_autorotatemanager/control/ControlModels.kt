package com.example.lab4_autorotatemanager.control

/** Хто останнім змінив стан виконавчого пристрою (актуатора). */
enum class ControlSource(val label: String) {
    AUTO("авто · DecisionEngine"),
    MANUAL("вручну · Manual Control"),
    REMOTE("хмара · Firebase"),
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

/** Спосіб отримання команд із хмари. */
enum class RemoteMode(val label: String) {
    SDK("Firebase SDK · ValueEventListener"),
    SSE("REST Streaming (SSE) · OkHttp"),
    NONE("не налаштовано")
}

/** Стан каналу Cloud-to-Device. */
enum class RemoteConnection(val label: String) {
    DISCONNECTED("відключено"),
    CONNECTING("підключення…"),
    CONNECTED("підключено"),
    ERROR("помилка")
}

/** Стан екрана «Керування». */
data class ControlUiState(
    // DecisionEngine
    val autoEnabled: Boolean = true,
    val threshold: Float = DecisionEngine.DEFAULT_THRESHOLD,
    val tilt: Float = 0f,
    val alarmActive: Boolean = false,
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
