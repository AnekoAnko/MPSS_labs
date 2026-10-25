package com.example.lab7_autorotatemanager.cloud

/** Статус хмарної синхронізації (колір індикатора визначається в UI). */
enum class SyncStatus(val label: String) {
    DISABLED("Вимкнено"),
    IDLE("Очікування даних"),
    SENDING("Надсилання…"),
    SUCCESS("Синхронізовано"),
    PENDING("Pending — немає мережі"),
    ERROR("Помилка запиту")
}

// State — усе, що показує картка «Хмарна синхронізація» (ЛР №3).
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

// Action — дії користувача на картці та події життєвого циклу Activity.
sealed interface CloudAction {
    /** onStart Activity: стежимо за мережею. */
    data object Start : CloudAction
    /** onStop Activity (окрім повороту екрана): знімаємо NetworkCallback. */
    data object Stop : CloudAction
    data class SetEnabled(val enabled: Boolean) : CloudAction
    data object SendNow : CloudAction
}

// Event — разові повідомлення (Toast).
sealed interface CloudEvent {
    data class ShowMessage(val text: String) : CloudEvent
}
