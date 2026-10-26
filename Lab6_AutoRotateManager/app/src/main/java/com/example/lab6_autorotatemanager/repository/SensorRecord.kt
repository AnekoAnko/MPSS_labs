package com.example.lab6_autorotatemanager.repository

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of sensor_records. Created only via SensorReading.toRecord().
 * sensorValue = tilt (0..180°), actionTriggered = alarm fired, synced = already uploaded.
 */
@Entity(
    tableName = "sensor_records",
    indices = [Index(value = ["timestamp"]), Index(value = ["synced"])]
)
data class SensorRecord(
    /** Primary key; 0 means auto-generate. */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Sample time, epoch ms. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** Sensor value: tilt from vertical, degrees. */
    @ColumnInfo(name = "sensor_value")
    val sensorValue: Float,

    /** Whether the alarm fired at this moment. */
    @ColumnInfo(name = "action_triggered")
    val actionTriggered: Boolean,

    /** Device orientation (PORTRAIT, FLAT, …). */
    @ColumnInfo(name = "orientation")
    val orientation: String,

    /** Raw axes, m/s², kept so the record can be uploaded later. */
    @ColumnInfo(name = "x")
    val x: Float = 0f,
    @ColumnInfo(name = "y")
    val y: Float = 0f,
    @ColumnInfo(name = "z")
    val z: Float = 0f,

    /** Whether the record was uploaded (false = pending). */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false
)
