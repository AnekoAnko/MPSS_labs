package com.example.lab3_autorotatemanager.cloud

import kotlin.math.abs
import kotlin.math.max

/**
 * Limits uploads: at most once per [intervalMs] when there is new data,
 * or immediately when any axis changes by more than [delta] m/s² (but not more often than [minGapMs]).
 */
class SendPolicy(
    val intervalMs: Long = 5_000,
    val delta: Float = 2.0f,
    val minGapMs: Long = 1_000
) {
    private var lastSent: SensorData? = null
    private var lastSentAt = 0L

    /** True if any axis changed by more than delta since the last upload. */
    fun deltaExceeded(sample: SensorData, now: Long): Boolean {
        val prev = lastSent ?: return true
        if (now - lastSentAt < minGapMs) return false
        val change = max(abs(sample.x - prev.x), max(abs(sample.y - prev.y), abs(sample.z - prev.z)))
        return change > delta
    }

    /** True if the interval passed and there is a new sample. */
    fun intervalElapsed(sample: SensorData, now: Long): Boolean {
        val prev = lastSent ?: return true
        return now - lastSentAt >= intervalMs && sample.timestamp > prev.timestamp
    }

    fun markSent(sample: SensorData, now: Long) {
        lastSent = sample
        lastSentAt = now
    }
}
