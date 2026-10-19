package com.example.lab5_autorotatemanager.repository

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ЛР №5: Entity — один рядок таблиці sensor_records у локальній базі SQLite.
 * Створюється лише з SensorReading.toRecord() у репозиторії.
 *
 * sensorValue — кут нахилу пристрою від вертикалі (0..180°), який рахує
 * DecisionEngine з ЛР №4 за відфільтрованими даними акселерометра (ЛР №1).
 * actionTriggered — true, якщо в момент запису автоматика ЛР №4 увімкнула
 * виконавчі пристрої (тривога: сирена + прожектор).
 * synced — чи запис уже відправлено в хмару; false → його відправить syncPending().
 */
@Entity(
    tableName = "sensor_records",
    indices = [Index(value = ["timestamp"]), Index(value = ["synced"])]
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
    val orientation: String,

    /** Сирі осі акселерометра, м/с² — щоб пізніше відправити запис у хмару повністю. */
    @ColumnInfo(name = "x")
    val x: Float = 0f,
    @ColumnInfo(name = "y")
    val y: Float = 0f,
    @ColumnInfo(name = "z")
    val z: Float = 0f,

    /** Чи відправлено запис у хмару (false → статус Pending). */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false
)
