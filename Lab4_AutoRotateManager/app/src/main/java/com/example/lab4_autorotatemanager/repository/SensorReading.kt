package com.example.lab4_autorotatemanager.repository

import kotlin.math.sqrt

/**
 * One sample as used by ViewModels and state.
 * SensorPayload (cloud JSON) is a separate model; toPayload() converts between them.
 */
data class SensorReading(
    /** Filtered accelerometer values, m/s². */
    val x: Float,
    val y: Float,
    val z: Float,
    /** Tilt from vertical, degrees. */
    val tilt: Float,
    /** Device orientation (PORTRAIT, FLAT, …). */
    val orientation: String,
    /** Whether the alarm fired at this moment. */
    val actionTriggered: Boolean = false,
    /** Sample time, epoch ms. */
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Magnitude |a|, m/s². */
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)
}

/** SensorReading → cloud JSON. */
fun SensorReading.toPayload(deviceId: String) = SensorPayload(
    deviceId = deviceId,
    type = SensorPayload.TYPE_ACCELEROMETER,
    x = x.round2(),
    y = y.round2(),
    z = z.round2(),
    value = magnitude.round2(),
    tilt = tilt.round1(),
    orientation = orientation,
    actionTriggered = actionTriggered,
    timestamp = timestamp
)

private fun Float.round2(): Float = Math.round(this * 100f) / 100f

private fun Float.round1(): Float = Math.round(this * 10f) / 10f
