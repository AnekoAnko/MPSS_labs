package com.example.lab3_autorotatemanager.cloud

import kotlin.math.abs
import kotlin.math.max

/**
 * Політика відправки, щоб не перевантажувати мережу:
 *  • за інтервалом — не частіше ніж раз на [intervalMs], і лише якщо з'явилися нові дані;
 *  • за зміною — одразу, якщо будь-яка вісь змінилася більше ніж на [delta] м/с²,
 *    але не частіше ніж раз на [minGapMs].
 */
class SendPolicy(
    val intervalMs: Long = 5_000,
    val delta: Float = 2.0f,
    val minGapMs: Long = 1_000
) {
    private var lastSent: SensorData? = null
    private var lastSentAt = 0L

    /** Чи змінилось значення на величину Δ відносно останнього відправленого. */
    fun deltaExceeded(sample: SensorData, now: Long): Boolean {
        val prev = lastSent ?: return true
        if (now - lastSentAt < minGapMs) return false
        val change = max(abs(sample.x - prev.x), max(abs(sample.y - prev.y), abs(sample.z - prev.z)))
        return change > delta
    }

    /** Чи минув інтервал і чи є новий вимір після останньої відправки. */
    fun intervalElapsed(sample: SensorData, now: Long): Boolean {
        val prev = lastSent ?: return true
        return now - lastSentAt >= intervalMs && sample.timestamp > prev.timestamp
    }

    fun markSent(sample: SensorData, now: Long) {
        lastSent = sample
        lastSentAt = now
    }
}
