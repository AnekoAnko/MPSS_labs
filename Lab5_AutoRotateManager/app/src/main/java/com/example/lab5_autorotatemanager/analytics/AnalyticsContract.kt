package com.example.lab5_autorotatemanager.analytics

import com.example.lab5_autorotatemanager.repository.PeriodStats
import com.example.lab5_autorotatemanager.repository.SensorRecord

/** Рядок історії спрацювань, уже підготовлений для показу. */
data class TriggerItem(val time: String, val value: Float, val orientation: String)

// State — усе, що видно на вкладці «Аналітика» (ЛР №5).
data class AnalyticsState(
    val recording: Boolean = true,
    /** Скільки записів зроблено за сесію. */
    val writes: Int = 0,
    val lastWrite: String? = null,
    val error: String? = null,
    /** Останні CHART_POINTS записів для графіка (від старих до нових). */
    val points: List<SensorRecord> = emptyList(),
    /** Історія спрацювань системи безпеки. */
    val history: List<TriggerItem> = emptyList(),
    /** Кількість рядків у таблиці sensor_records. */
    val recordCount: Int = 0,
    /** Агрегати за останню годину (SQL MIN/MAX/AVG). */
    val hourStats: PeriodStats? = null,
    /** Поточний поріг DecisionEngine (ЛР №4) — пунктир на графіку. */
    val threshold: Float = 60f
)

// Action — дії користувача.
sealed interface AnalyticsAction {
    data class SetRecording(val enabled: Boolean) : AnalyticsAction
    data object ClearHistory : AnalyticsAction
}

// Event — разові повідомлення.
sealed interface AnalyticsEvent {
    data class ShowMessage(val text: String) : AnalyticsEvent
}
