package com.example.lab7_autorotatemanager.analytics

import kotlin.math.abs

/** Why a sample was stored. */
enum class RecordReason(val label: String) {
    FIRST("перший вимір"),
    EVENT("спрацювання актуаторів"),
    CHANGE("суттєва зміна"),
    TIMER("таймер"),
    FAST("біг · прискорений запис")
}

/**
 * Decides which samples go to the database, so it doesn't fill up with noise. A sample is stored if:
 * it is the first one, an alarm just fired (force), the value changed by ≥ [minDelta],
 * or [heartbeatMs] has passed. Writes are at least [minIntervalMs] apart.
 */
class RecordPolicy(
    val minDelta: Float = DEFAULT_MIN_DELTA,
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS
) {
    private var lastValue: Float? = null
    private var lastTime = 0L

    /** Fast mode (running): store every [FAST_INTERVAL_MS] without the minDelta check. */
    var fastMode = false

    /** Returns the reason to store the sample, or null to skip it. */
    fun check(value: Float, nowMs: Long, force: Boolean = false): RecordReason? {
        val previous = lastValue
        val reason = when {
            previous == null -> RecordReason.FIRST
            force -> RecordReason.EVENT
            fastMode && nowMs - lastTime >= FAST_INTERVAL_MS -> RecordReason.FAST
            nowMs - lastTime < minIntervalMs -> null
            abs(value - previous) >= minDelta -> RecordReason.CHANGE
            nowMs - lastTime >= heartbeatMs -> RecordReason.TIMER
            else -> null
        }
        if (reason != null) {
            lastValue = value
            lastTime = nowMs
        }
        return reason
    }

    fun reset() {
        lastValue = null
        lastTime = 0L
    }

    companion object {
        const val DEFAULT_MIN_DELTA = 3f          // degrees
        const val DEFAULT_MIN_INTERVAL_MS = 250L  // at most 4 writes/s
        const val DEFAULT_HEARTBEAT_MS = 5_000L   // once per 5 s when idle
        const val FAST_INTERVAL_MS = 200L         // 5 writes/s while running
    }
}
