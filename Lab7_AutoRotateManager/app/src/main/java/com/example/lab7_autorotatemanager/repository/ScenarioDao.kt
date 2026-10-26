package com.example.lab7_autorotatemanager.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Queries for the scenario_rules table. */
@Dao
interface ScenarioDao {

    /** Live list of rules: Room re-runs the query after every change. */
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
