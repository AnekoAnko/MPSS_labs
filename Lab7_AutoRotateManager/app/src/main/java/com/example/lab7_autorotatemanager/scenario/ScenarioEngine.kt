package com.example.lab7_autorotatemanager.scenario

import com.example.lab7_autorotatemanager.repository.Comparison
import com.example.lab7_autorotatemanager.repository.ScenarioRule
import kotlin.math.abs
import kotlin.math.max

/** Правило ввімкнулося (active = true) або вимкнулося (false). */
data class ScenarioTransition(val rule: ScenarioRule, val active: Boolean, val value: Float)

/**
 * ЛР №7: двигун Smart-сценаріїв «IF умова THEN дія».
 *
 * Захист від хибних спрацювань (крім медіанного фільтра перед ним):
 *  • витримка holdMs — умова має триматися безперервно, одиночний вимір нічого не вмикає;
 *  • гістерезис — правило вимикається, лише коли значення відійде від порога на 5 % (мінімум 0,2);
 *  • затримка вимкнення [releaseMs] — коливання навколо порога (тряска) не «блимають» актуатором.
 * Клас не залежить від Android, час передається параметром — його легко перевірити тестом.
 */
class ScenarioEngine(
    private val releaseMs: Long = DEFAULT_RELEASE_MS,
    private val hysteresisRatio: Float = HYSTERESIS_RATIO
) {
    /** id правила → момент, з якого умова виконується (ще не спрацювало). */
    private val pendingSince = HashMap<Long, Long>()
    /** id правила → момент, з якого умова вже не виконується (правило ще активне). */
    private val releaseSince = HashMap<Long, Long>()
    /** Активні правила (спрацювали і ще не вимкнулися). */
    private val active = HashMap<Long, ScenarioRule>()

    val activeIds: Set<Long> get() = active.keys

    fun evaluate(rules: List<ScenarioRule>, x: Float, y: Float, z: Float, nowMs: Long): List<ScenarioTransition> {
        val out = ArrayList<ScenarioTransition>()
        val enabled = rules.filter { it.enabled }

        // Правило видалили або вимкнули, поки воно було активне → знімаємо його дію
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
                    pendingSince.remove(rule.id)   // викид не протримався holdMs — скидаємо
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
                    active[rule.id] = rule   // правило могли відредагувати
                }
            }
        }
        return out
    }

    /** Зняти всі активні правила (наприклад, сценарії вимкнули перемикачем). */
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
