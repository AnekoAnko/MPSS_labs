package com.example.lab7_autorotatemanager.scenario

import com.example.lab7_autorotatemanager.helpers.NoiseLevel
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.Comparison
import com.example.lab7_autorotatemanager.repository.Metric
import com.example.lab7_autorotatemanager.repository.ScenarioRule

/** Rule + whether it is active right now. */
data class RuleItem(val rule: ScenarioRule, val active: Boolean)

/** "New rule" form; threshold is raw text validated by the ViewModel. */
data class RuleDraft(
    val metric: Metric = Metric.X,
    val comparison: Comparison = Comparison.GREATER,
    val threshold: String = "10",
    val action: ActuatorType = ActuatorType.VIBRATION,
    val holdMs: Long = ScenarioRule.DEFAULT_HOLD_MS
)

// State: the Scenarios tab.
data class ScenarioState(
    val enabled: Boolean = true,
    val noise: NoiseLevel = NoiseLevel.MEDIUM,
    val rules: List<RuleItem> = emptyList(),
    val draft: RuleDraft = RuleDraft(),
    val draftError: String? = null,
    /** Current values after noise filtering (what the rule engine sees). */
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val dynamic: Float = 0f,
    val tilt: Float = 0f,
    val firedCount: Int = 0,
    val log: List<String> = emptyList()
)

// Action: user intents.
sealed interface ScenarioAction {
    data class SetEnabled(val enabled: Boolean) : ScenarioAction
    data class SetNoise(val level: NoiseLevel) : ScenarioAction
    data class ToggleRule(val id: Long, val enabled: Boolean) : ScenarioAction
    data class DeleteRule(val id: Long) : ScenarioAction
    data class DraftMetric(val metric: Metric) : ScenarioAction
    data class DraftComparison(val comparison: Comparison) : ScenarioAction
    data class DraftThreshold(val text: String) : ScenarioAction
    data class DraftAction(val action: ActuatorType) : ScenarioAction
    data class DraftHold(val ms: Long) : ScenarioAction
    data object AddRule : ScenarioAction
}

// Event: one-off messages.
sealed interface ScenarioEvent {
    data class ShowMessage(val text: String) : ScenarioEvent
}
