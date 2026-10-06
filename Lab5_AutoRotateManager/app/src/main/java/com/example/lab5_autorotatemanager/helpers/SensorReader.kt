package com.example.lab5_autorotatemanager.helpers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Один вимір акселерометра, м/с²; timestampNs — SensorEvent.timestamp. */
data class SensorSample(val x: Float, val y: Float, val z: Float, val timestampNs: Long)

/**
 * Помічник: перетворює SensorEventListener (Android API) на Flow.
 * Слухач реєструється, коли Flow починають збирати, і знімається при cancel()
 * (MonitorAction.Stop у onPause) — сенсор не працює у фоні.
 */
class SensorReader(context: Context) {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Чи є акселерометр на пристрої. */
    val isAvailable: Boolean get() = sensor != null

    fun readings(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_UI): Flow<SensorSample> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                trySend(SensorSample(e.values[0], e.values[1], e.values[2], e.timestamp))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager?.registerListener(listener, sensor, samplingPeriodUs)
        awaitClose { manager?.unregisterListener(listener) }   // викликається при cancel()
    }
}
