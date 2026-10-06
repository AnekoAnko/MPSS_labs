package com.example.lab6_autorotatemanager.repository

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ЛР №5: Entity — один рядок таблиці sensor_records у локальній базі SQLite.
 *
 * sensorValue — кут нахилу пристрою від вертикалі (0..180°), який рахує
 * DecisionEngine з ЛР №4 за відфільтрованими даними акселерометра (ЛР №1).
 * actionTriggered — true, якщо в момент запису автоматика ЛР №4 увімкнула
 * виконавчі пристрої (тривога: сирена + прожектор).
 */
@Entity(
    tableName = "sensor_records",
    indices = [Index(value = ["timestamp"])]
)
data class SensorRecord(
    /** Первинний ключ; 0 означає «згенерувати автоматично» (AUTOINCREMENT). */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Час вимірювання, мс від 01.01.1970 (Unix epoch). */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** Значення сенсора: кут нахилу від вертикалі, градуси. */
    @ColumnInfo(name = "sensor_value")
    val sensorValue: Float,

    /** Чи спрацювала в цей момент система безпеки (актуатори ЛР №4). */
    @ColumnInfo(name = "action_triggered")
    val actionTriggered: Boolean,

    /** Додатково: стан Auto-Rotate Manager з ЛР №1 (PORTRAIT, FLAT, …). */
    @ColumnInfo(name = "orientation")
    val orientation: String
)
