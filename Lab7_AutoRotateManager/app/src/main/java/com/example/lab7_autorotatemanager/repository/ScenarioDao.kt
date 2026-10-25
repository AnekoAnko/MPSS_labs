package com.example.lab7_autorotatemanager.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** ЛР №7: операції з таблицею Smart-сценаріїв. */
@Dao
interface ScenarioDao {

    /** «Живий» список правил: Room повторює запит після кожної зміни таблиці. */
    @Query("SELECT * FROM scenario_rules ORDER BY id ASC")
    fun observeAll(): Flow<List<ScenarioRecord>>

    @Query("SELECT COUNT(*) FROM scenario_rules")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ScenarioRecord): Long

    @Query("UPDATE scenario_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM scenario_rules WHERE id = :id")
    suspend fun delete(id: Long)
}
