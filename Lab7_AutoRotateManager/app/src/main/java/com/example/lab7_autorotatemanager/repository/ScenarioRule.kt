package com.example.lab7_autorotatemanager.repository

import com.example.lab7_autorotatemanager.control.DecisionEngine
import kotlin.math.abs
import kotlin.math.sqrt

/** Value checked by a rule. */
enum class Metric(val label: String, val unit: String) {
    X("X", "м/с²"),
    Y("Y", "м/с²"),
    Z("Z", "м/с²"),
    /** Magnitude |a|; ≈ 9.81 at rest. */
    MAGNITUDE("|a|", "м/с²"),
    /** Dynamic part ||a| − g|: ≈ 0 at rest, grows with motion and shaking. */
    DYNAMIC("|a|−g", "м/с²"),
    /** Tilt from vertical, same as DecisionEngine. */
    TILT("нахил", "°");

    /** Value of this metric for one sample (m/s²). */
    fun of(x: Float, y: Float, z: Float): Float {
        val m = sqrt(x * x + y * y + z * z)
        return when (this) {
            X -> x
            Y -> y
            Z -> z
            MAGNITUDE -> m
            DYNAMIC -> abs(m - GRAVITY)
            TILT -> DecisionEngine.tiltDegrees(x, y, z)
        }
    }

    companion object {
        const val GRAVITY = 9.80665f
    }
}

enum class Comparison(val symbol: String) {
    GREATER(">"),
    LESS("<");

    fun test(value: Float, threshold: Float): Boolean =
        if (this == GREATER) value > threshold else value < threshold
}

/** Rule "IF [metric] [comparison] [threshold] THEN [action]". Stored in Room as ScenarioRecord. */
data class ScenarioRule(
    val id: Long = 0,
    val metric: Metric,
    val comparison: Comparison,
    val threshold: Float,
    val action: ActuatorType,
    val enabled: Boolean = true,
    /** How long the condition must hold before the rule fires (filters single spikes). */
    val holdMs: Long = DEFAULT_HOLD_MS
) {
    /** Human-readable rule text for the UI. */
    fun describe(): String =
        "Якщо ${metric.label} ${comparison.symbol} ${"%.1f".format(threshold)} ${metric.unit} → ${action.label}"

    companion object {
        const val DEFAULT_HOLD_MS = 300L

        /** Rules created on first launch. */
        val DEFAULTS = listOf(
            ScenarioRule(metric = Metric.X, comparison = Comparison.GREATER, threshold = 10f, action = ActuatorType.VIBRATION),
            ScenarioRule(metric = Metric.DYNAMIC, comparison = Comparison.GREATER, threshold = 6f, action = ActuatorType.HUB_RELAY),
            ScenarioRule(metric = Metric.TILT, comparison = Comparison.GREATER, threshold = 150f, action = ActuatorType.LAMP, enabled = false)
        )
    }
}

fun ScenarioRule.toRecord() = ScenarioRecord(
    id = id,
    metric = metric.name,
    comparison = comparison.name,
    threshold = threshold,
    action = action.name,
    enabled = enabled,
    holdMs = holdMs
)

/** Unknown values (e.g. after an enum change) are skipped instead of crashing. */
fun ScenarioRecord.toRule(): ScenarioRule? = try {
    ScenarioRule(
        id = id,
        metric = Metric.valueOf(metric),
        comparison = Comparison.valueOf(comparison),
        threshold = threshold,
        action = ActuatorType.valueOf(action),
        enabled = enabled,
        holdMs = holdMs
    )
} catch (e: IllegalArgumentException) {
    null
}
