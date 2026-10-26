package com.example.lab7_autorotatemanager.scenario

import com.example.lab7_autorotatemanager.repository.Comparison
import com.example.lab7_autorotatemanager.repository.ScenarioRule
import kotlin.math.abs
import kotlin.math.max

/** A rule turned on (active = true) or off. */
data class ScenarioTransition(val rule: ScenarioRule, val active: Boolean, val value: Float)

/**
 * Rule engine "IF condition THEN action". Protection against false triggers:
 * hold time (condition must last holdMs), hysteresis (5 %, min 0.2)
 * and a release delay ([releaseMs]) so oscillation near the threshold doesn't toggle actuators.
 */
class ScenarioEngine(
    private val releaseMs: Long = DEFAULT_RELEASE_MS,
    private val hysteresisRatio: Float = HYSTERESIS_RATIO
) {
    /** rule id → since when the condition holds (not fired yet). */
    private val pendingSince = HashMap<Long, Long>()
    /** rule id → since when the condition stopped holding (still active). */
    private val releaseSince = HashMap<Long, Long>()
    /** Active rules. */
    private val active = HashMap<Long, ScenarioRule>()

    val activeIds: Set<Long> get() = active.keys

    fun evaluate(rules: List<ScenarioRule>, x: Float, y: Float, z: Float, nowMs: Long): List<ScenarioTransition> {
        val out = ArrayList<ScenarioTransition>()
        val enabled = rules.filter { it.enabled }

        // Rule removed or disabled while active: release its action
        val ids = enabled.mapTo(HashSet()) { it.id }
        for (id in active.keys.filter { it !in ids }) {
            out += ScenarioTransition(active.remove(id)!!, active = false, value = Float.NaN)
            releaseSince.remove(id)
        }
        pendingSince.keys.retainAll(ids)

        for (rule in enabled) {
            val value = rule.metric.of(x, y, z)
            if (rule.id !in active) {
                if (rule.comparison.test(value, rule.threshold)) {
                    val since = pendingSince.getOrPut(rule.id) { nowMs }
                    if (nowMs - since >= rule.holdMs) {
                        pendingSince.remove(rule.id)
                        active[rule.id] = rule
                        out += ScenarioTransition(rule, active = true, value = value)
                    }
                } else {
                    pendingSince.remove(rule.id)   // spike didn't last holdMs: reset
                }
            } else {
                if (isReleased(rule, value)) {
                    val since = releaseSince.getOrPut(rule.id) { nowMs }
                    if (nowMs - since >= releaseMs) {
                        releaseSince.remove(rule.id)
                        active.remove(rule.id)
                        out += ScenarioTransition(rule, active = false, value = value)
                    }
                } else {
                    releaseSince.remove(rule.id)
                    active[rule.id] = rule   // the rule may have been edited
                }
            }
        }
        return out
    }

    /** Releases all active rules (e.g. scenarios switched off). */
    fun releaseAll(): List<ScenarioTransition> {
        val out = active.values.map { ScenarioTransition(it, active = false, value = Float.NaN) }
        active.clear(); pendingSince.clear(); releaseSince.clear()
        return out
    }

    private fun isReleased(rule: ScenarioRule, value: Float): Boolean {
        val h = max(abs(rule.threshold) * hysteresisRatio, MIN_HYSTERESIS)
        return when (rule.comparison) {
            Comparison.GREATER -> value < rule.threshold - h
            Comparison.LESS -> value > rule.threshold + h
        }
    }

    companion object {
        const val DEFAULT_RELEASE_MS = 1_000L
        const val HYSTERESIS_RATIO = 0.05f
        const val MIN_HYSTERESIS = 0.2f
    }
}
