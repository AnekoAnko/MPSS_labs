package com.example.lab7_autorotatemanager.repository

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** ЛР №7: величина, яку перевіряє Smart-сценарій. */
enum class Metric(val label: String, val unit: String) {
    X("X", "м/с²"),
    Y("Y", "м/с²"),
    Z("Z", "м/с²"),
    /** Модуль вектора |a|; у спокої ≈ 9,81. */
    MAGNITUDE("|a|", "м/с²"),
    /** Динамічна складова ||a| − g|: ≈ 0 у спокої, росте під час руху й тряски. */
    DYNAMIC("|a|−g", "м/с²"),
    /** Кут нахилу від вертикалі, як у DecisionEngine ЛР №4. */
    TILT("нахил", "°");

    /** Значення величини для одного виміру (x, y, z — м/с²). */
    fun of(x: Float, y: Float, z: Float): Float {
        val m = sqrt(x * x + y * y + z * z)
        return when (this) {
            X -> x
            Y -> y
            Z -> z
            MAGNITUDE -> m
            DYNAMIC -> abs(m - GRAVITY)
            TILT -> if (m < 1e-3f) 0f else Math.toDegrees(acos((y / m).coerceIn(-1f, 1f).toDouble())).toFloat()
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

/**
 * ЛР №7: правило «Якщо [metric] [comparison] [threshold], тоді [action]».
 * Модель для ViewModel і State; у Room зберігається як ScenarioRecord.
 */
data class ScenarioRule(
    val id: Long = 0,
    val metric: Metric,
    val comparison: Comparison,
    val threshold: Float,
    val action: ActuatorType,
    val enabled: Boolean = true,
    /** Скільки умова має триматися, щоб правило спрацювало (захист від одиночних викидів). */
    val holdMs: Long = DEFAULT_HOLD_MS
) {
    /** «Якщо X > 10 м/с² → Вібрація». */
    fun describe(): String =
        "Якщо ${metric.label} ${comparison.symbol} ${"%.1f".format(threshold)} ${metric.unit} → ${action.label}"

    companion object {
        const val DEFAULT_HOLD_MS = 300L

        /** Правила, які створюються при першому запуску. */
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

/** Невідомі значення (наприклад, після зміни enum) не ламають додаток — рядок пропускається. */
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
