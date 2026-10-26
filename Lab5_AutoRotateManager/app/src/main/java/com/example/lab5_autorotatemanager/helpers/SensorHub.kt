package com.example.lab5_autorotatemanager.helpers

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Low-pass filter: smooths noise and keeps the gravity component. x = α·x + (1 − α)·ax */
class LowPassFilter(private val alpha: Float = ALPHA) {
    private var x = 0f
    private var y = 0f
    private var z = 0f

    fun apply(s: SensorSample): SensorSample {
        x = alpha * x + (1 - alpha) * s.x
        y = alpha * y + (1 - alpha) * s.y
        z = alpha * z + (1 - alpha) * s.z
        return SensorSample(x, y, z, s.timestampNs)
    }

    companion object {
        /** Filter coefficient (0..1): higher is smoother but slower. */
        const val ALPHA = 0.8f
    }
}

/**
 * Shared stream of accelerometer data for all screens.
 * MonitorViewModel publishes while the screen is visible; others subscribe to [raw] or [filtered].
 */
class SensorHub {

    private val filter = LowPassFilter()

    private val _raw = MutableSharedFlow<SensorSample>(
        extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Raw event.values. */
    val raw: SharedFlow<SensorSample> = _raw.asSharedFlow()

    private val _filtered = MutableSharedFlow<SensorSample>(
        extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Low-pass filtered values. */
    val filtered: SharedFlow<SensorSample> = _filtered.asSharedFlow()

    /** Takes a raw sample, emits raw and filtered values, returns the filtered one. */
    fun publish(sample: SensorSample): SensorSample {
        val smooth = filter.apply(sample)
        _raw.tryEmit(sample)
        _filtered.tryEmit(smooth)
        return smooth
    }

    private companion object {
        const val BUFFER = 64
    }
}
