package com.example.lab7_autorotatemanager.cloud

/** Cloud sync status (indicator color is chosen in the UI). */
enum class SyncStatus(val label: String) {
    DISABLED("Вимкнено"),
    IDLE("Очікування даних"),
    SENDING("Надсилання…"),
    SUCCESS("Синхронізовано"),
    PENDING("Pending — немає мережі"),
    ERROR("Помилка запиту")
}

// State: the cloud sync card.
data class CloudState(
    val enabled: Boolean = true,
    val configured: Boolean = true,
    val online: Boolean = true,
    val status: SyncStatus = SyncStatus.IDLE,
    val deviceId: String = "",
    val sentCount: Int = 0,
    val pendingCount: Int = 0,
    val lastSuccessTime: String? = null,
    val lastRecordKey: String? = null,
    val lastError: String? = null,
    val lastPayloadJson: String? = null,
    val recent: List<String> = emptyList()
)

// Action: user intents and Activity lifecycle events.
sealed interface CloudAction {
    /** onStart: start watching the network. */
    data object Start : CloudAction
    /** onStop (not on rotation): stop watching the network. */
    data object Stop : CloudAction
    data class SetEnabled(val enabled: Boolean) : CloudAction
    data object SendNow : CloudAction
}

// Event: one-off messages (Toast).
sealed interface CloudEvent {
    data class ShowMessage(val text: String) : CloudEvent
}
