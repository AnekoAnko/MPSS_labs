package com.example.lab5_autorotatemanager.helpers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One accelerometer sample, m/s²; timestampNs = SensorEvent.timestamp. */
data class SensorSample(val x: Float, val y: Float, val z: Float, val timestampNs: Long)

/**
 * Wraps SensorEventListener as a Flow. The listener is registered on collect
 * and removed on cancel, so the sensor never runs in the background.
 */
class SensorReader(context: Context) {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Whether the device has an accelerometer. */
    val isAvailable: Boolean get() = sensor != null

    fun readings(samplingPeriodUs: Int = DELAY_UI): Flow<SensorSample> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                trySend(SensorSample(e.values[0], e.values[1], e.values[2], e.timestamp))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager?.registerListener(listener, sensor, samplingPeriodUs)
        awaitClose { manager?.unregisterListener(listener) }   // called on cancel()
    }

    companion object {
        /** Standard UI rate (≈ 15 Hz). */
        const val DELAY_UI = SensorManager.SENSOR_DELAY_UI

        /** 20 Hz (50,000 µs), the rate the ML model was trained on. */
        const val PERIOD_20_HZ_US = 50_000

        /** Standard gravity, m/s². */
        const val GRAVITY = SensorManager.GRAVITY_EARTH
    }
}
