package com.example.lab7_autorotatemanager.repository

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ЛР №7: рядок таблиці scenario_rules — одне Smart-правило користувача.
 * Enum зберігаємо рядками (name), тому TypeConverter не потрібен.
 */
@Entity(tableName = "scenario_rules")
data class ScenarioRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "metric")
    val metric: String,
    @ColumnInfo(name = "comparison")
    val comparison: String,
    @ColumnInfo(name = "threshold")
    val threshold: Float,
    @ColumnInfo(name = "action")
    val action: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "hold_ms")
    val holdMs: Long
)
