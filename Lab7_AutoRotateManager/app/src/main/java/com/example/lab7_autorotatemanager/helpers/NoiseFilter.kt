package com.example.lab7_autorotatemanager.helpers

/** Noise reduction level for smart scenarios; [window] = median filter size in samples. */
enum class NoiseLevel(val label: String, val window: Int) {
    OFF("вимкнено", 1),
    LOW("слабке (3)", 3),
    MEDIUM("середнє (5)", 5),
    HIGH("сильне (9)", 9)
}

/**
 * Per-axis median filter. Removes single spikes (shorter than half the window)
 * while keeping real motion edges almost intact.
 */
class MedianNoiseFilter(window: Int = NoiseLevel.MEDIUM.window) {

    var window: Int = window.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            reset()
        }

    private val xs = ArrayDeque<Float>()
    private val ys = ArrayDeque<Float>()
    private val zs = ArrayDeque<Float>()

    /** Adds a sample and returns the filtered (x, y, z). */
    fun apply(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        if (window == 1) return Triple(x, y, z)
        push(xs, x); push(ys, y); push(zs, z)
        return Triple(median(xs), median(ys), median(zs))
    }

    fun reset() {
        xs.clear(); ys.clear(); zs.clear()
    }

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > window) q.removeFirst()
    }

    private fun median(q: ArrayDeque<Float>): Float {
        val sorted = q.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
