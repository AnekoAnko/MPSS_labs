package com.example.lab4_autorotatemanager.helpers

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Низькочастотний фільтр (ЛР №1): згладжує шум, залишаючи складову гравітації.
 *   x = α·x + (1 − α)·ax
 */
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
        /** Коефіцієнт фільтра (0..1): більше — плавніше, але повільніше. */
        const val ALPHA = 0.8f
    }
}

/**
 * Спільна «труба» з даними акселерометра для всіх екранів.
 * Один сенсор — багато споживачів: монітор (ЛР №1), хмара (ЛР №3), керування (ЛР №4)…
 * Дані в неї кладе MonitorViewModel, поки екран видимий (MonitorAction.Start/Stop),
 * а інші ViewModel лише підписуються на [raw] або [filtered].
 * Створюється один раз на весь додаток (AppGraph).
 */
class SensorHub {

    private val filter = LowPassFilter()

    private val _raw = MutableSharedFlow<SensorSample>(
        extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Сирі значення event.values. */
    val raw: SharedFlow<SensorSample> = _raw.asSharedFlow()

    private val _filtered = MutableSharedFlow<SensorSample>(
        extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Значення після низькочастотного фільтра. */
    val filtered: SharedFlow<SensorSample> = _filtered.asSharedFlow()

    /** Приймає сирий вимір, повертає відфільтрований і розсилає обидва підписникам. */
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
