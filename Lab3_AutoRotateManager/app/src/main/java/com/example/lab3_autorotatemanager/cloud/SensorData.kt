package com.example.lab3_autorotatemanager.cloud

/** Sample payload sent to the cloud as JSON. */
data class SensorData(
    /** Device id. */
    val deviceId: String,
    /** Sensor type: ACCELEROMETER. */
    val type: String,
    /** Acceleration per axis, m/s². */
    val x: Float,
    val y: Float,
    val z: Float,
    /** Magnitude |a|, m/s². */
    val value: Float,
    /** Device orientation (PORTRAIT, FLAT, …). */
    val orientation: String,
    /** Sample time, epoch ms. */
    val timestamp: Long = System.currentTimeMillis()
)

/** Firebase reply to POST: key of the created record. */
data class PushResponse(val name: String?)
