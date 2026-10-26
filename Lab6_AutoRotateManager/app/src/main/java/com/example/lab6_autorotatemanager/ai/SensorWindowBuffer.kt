package com.example.lab6_autorotatemanager.ai

import com.example.lab6_autorotatemanager.helpers.SensorReader

/**
 * Circular buffer holding the last [windowSize] accelerometer samples (≈ 1 s at 20 Hz).
 * New samples overwrite the oldest one at [head], so nothing is allocated per event.
 * Samples closer than [minPeriodNs] are dropped; [add] reports when a new inference is due (every [hop] samples).
 */
class SensorWindowBuffer(
    val windowSize: Int = WINDOW_SIZE,
    val hop: Int = HOP,
    private val minPeriodNs: Long = MIN_PERIOD_NS
) {
    private val data = FloatArray(windowSize * AXES)   // x0 y0 z0 x1 y1 z1 …
    private var head = 0                                // next write position
    private var lastAcceptedNs = Long.MIN_VALUE
    private var sinceLastInference = 0

    /** Number of samples currently in the window (0..windowSize). */
    var size = 0
        private set

    val isFull: Boolean get() = size == windowSize

    /** Adds a sample (timestamp in ns). Returns true when the window is full and a new inference is due. */
    fun add(x: Float, y: Float, z: Float, timestampNs: Long): Boolean {
        if (lastAcceptedNs != Long.MIN_VALUE && timestampNs - lastAcceptedNs < minPeriodNs) return false
        lastAcceptedNs = timestampNs

        val i = head * AXES
        data[i] = x
        data[i + 1] = y
        data[i + 2] = z
        head = (head + 1) % windowSize
        if (size < windowSize) size++

        sinceLastInference++
        if (isFull && sinceLastInference >= hop) {
            sinceLastInference = 0
            return true
        }
        return false
    }

    /**
     * Builds the model input [1, windowSize, 3] from oldest to newest sample:
     * subtracts the per-axis mean (removes gravity and device pose) and divides by g.
     * Returns a copy, so the buffer can keep filling while inference runs.
     */
    fun toModelInput(): FloatArray {
        check(isFull) { "Вікно ще не заповнене: $size/$windowSize" }
        val out = FloatArray(windowSize * AXES)
        val mean = FloatArray(AXES)
        for (t in 0 until windowSize) {
            val src = ((head + t) % windowSize) * AXES      // t = 0 is the oldest sample
            for (a in 0 until AXES) {
                out[t * AXES + a] = data[src + a]
                mean[a] += data[src + a]
            }
        }
        for (a in 0 until AXES) mean[a] /= windowSize
        for (t in 0 until windowSize) {
            for (a in 0 until AXES) {
                out[t * AXES + a] = (out[t * AXES + a] - mean[a]) / GRAVITY
            }
        }
        return out
    }

    fun clear() {
        data.fill(0f)
        head = 0
        size = 0
        sinceLastInference = 0
        lastAcceptedNs = Long.MIN_VALUE
    }

    companion object {
        const val AXES = 3
        /** Window size the model was trained on (20 samples ≈ 1 s at 20 Hz). */
        const val WINDOW_SIZE = 20
        /** Run inference every 5 samples (≈ 250 ms). */
        const val HOP = 5
        /** 20 Hz = 50 ms period; 45 ms leaves room for jitter. */
        const val MIN_PERIOD_NS = 45_000_000L
        /** Sensor sampling period for registerListener, µs (20 Hz). */
        const val SAMPLING_PERIOD_US = SensorReader.PERIOD_20_HZ_US
        const val GRAVITY = 9.80665f
    }
}
