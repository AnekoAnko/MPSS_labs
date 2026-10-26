package com.example.lab7_autorotatemanager.analytics

import com.example.lab7_autorotatemanager.repository.PeriodStats
import com.example.lab7_autorotatemanager.repository.SensorRecord

/** One row of the trigger history, formatted for display. */
data class TriggerItem(val time: String, val value: Float, val orientation: String)

// State: the Analytics tab.
data class AnalyticsState(
    val recording: Boolean = true,
    /** Writes made in this session. */
    val writes: Int = 0,
    /** Fast recording is on (model detected running). */
    val fastMode: Boolean = false,
    val lastWrite: String? = null,
    val error: String? = null,
    /** Last CHART_POINTS records for the chart, oldest first. */
    val points: List<SensorRecord> = emptyList(),
    /** History of alarm triggers. */
    val history: List<TriggerItem> = emptyList(),
    /** Number of rows in sensor_records. */
    val recordCount: Int = 0,
    /** Aggregates for the last hour (SQL MIN/MAX/AVG). */
    val hourStats: PeriodStats? = null,
    /** Current DecisionEngine threshold (dashed line on the chart). */
    val threshold: Float = 60f
)

// Action: user intents.
sealed interface AnalyticsAction {
    data class SetRecording(val enabled: Boolean) : AnalyticsAction
    data object ClearHistory : AnalyticsAction
}

// Event: one-off messages.
sealed interface AnalyticsEvent {
    data class ShowMessage(val text: String) : AnalyticsEvent
}
