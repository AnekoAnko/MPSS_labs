package com.example.lab4_autorotatemanager.control

import kotlin.math.acos
import kotlin.math.sqrt

/** Decision taken for each sensor sample. */
enum class Decision { ACTIVATE, DEACTIVATE, NO_CHANGE }

data class EngineResult(
    /** Tilt from the upright (portrait) position, degrees. */
    val tiltDeg: Float,
    val decision: Decision,
    val alarmActive: Boolean
)

/**
 * IF tilt > threshold THEN activate actuators.
 * tilt = arccos(y / |a|): 0° upright, 90° flat or landscape, 180° upside down.
 * Hysteresis (off below threshold − hysteresis) and a hold time prevent toggling near the threshold.
 */
class DecisionEngine(
    threshold: Float = DEFAULT_THRESHOLD,
    val hysteresis: Float = DEFAULT_HYSTERESIS,
    val holdMs: Long = DEFAULT_HOLD_MS
) {
    /** Threshold in degrees; can be changed from the UI or the cloud. */
    var threshold: Float = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        set(value) {
            field = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }

    /** Whether the alarm is active. */
    var alarmActive = false
        private set

    /** Since when the switch condition holds (null if it doesn't). */
    private var conditionSince: Long? = null

    fun evaluate(x: Float, y: Float, z: Float, nowMs: Long): EngineResult {
        val tilt = tiltDegrees(x, y, z)

        // on: tilt > threshold; off: tilt < threshold − hysteresis
        val conditionMet = if (!alarmActive) tilt > threshold else tilt < threshold - hysteresis
        if (!conditionMet) {
            conditionSince = null
            return EngineResult(tilt, Decision.NO_CHANGE, alarmActive)
        }

        val since = conditionSince ?: nowMs.also { conditionSince = it }
        if (nowMs - since < holdMs) {
            return EngineResult(tilt, Decision.NO_CHANGE, alarmActive)   // still waiting for the hold time
        }

        alarmActive = !alarmActive
        conditionSince = null
        val decision = if (alarmActive) Decision.ACTIVATE else Decision.DEACTIVATE
        return EngineResult(tilt, decision, alarmActive)
    }

    /** Resets the state (e.g. when automation is turned off). */
    fun reset() {
        alarmActive = false
        conditionSince = null
    }

    companion object {
        const val DEFAULT_THRESHOLD = 60f
        const val DEFAULT_HYSTERESIS = 10f
        const val DEFAULT_HOLD_MS = 300L
        const val MIN_THRESHOLD = 15f
        const val MAX_THRESHOLD = 170f

        /** Tilt from vertical using the gravity projection on Y, degrees (0..180). */
        fun tiltDegrees(x: Float, y: Float, z: Float): Float {
            val norm = sqrt(x * x + y * y + z * z)
            if (norm < 1f) return 0f   // no data yet or free fall
            val cos = (y / norm).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
    }
}
