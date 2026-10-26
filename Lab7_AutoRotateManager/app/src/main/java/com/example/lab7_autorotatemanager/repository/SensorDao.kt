package com.example.lab7_autorotatemanager.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Queries for sensor_records. Room generates the implementation with KSP and validates SQL at build time.
 * suspend functions run off the main thread; Flow queries re-emit after every table change.
 */
@Dao
interface SensorDao {

    /** Inserts a sample; returns the new row id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SensorRecord): Long

    /** Last [limit] records for the chart: inner SELECT takes the newest, outer sorts them oldest first. */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM sensor_records ORDER BY timestamp DESC LIMIT :limit
        ) ORDER BY timestamp ASC
        """
    )
    fun observeLatest(limit: Int): Flow<List<SensorRecord>>

    /** Same, one-shot (for checks and tests). */
    @Query("SELECT * FROM sensor_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<SensorRecord>

    /** Last [limit] records, newest first (dashboard). */
    @Query("SELECT * FROM sensor_records ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SensorRecord>>

    /** Alarm trigger history, newest first. */
    @Query("SELECT * FROM sensor_records WHERE action_triggered = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun observeTriggered(limit: Int): Flow<List<SensorRecord>>

    /** Total row count. */
    @Query("SELECT COUNT(*) FROM sensor_records")
    fun observeCount(): Flow<Int>

    /** Aggregates for a period, computed by SQLite. */
    @Query(
        """
        SELECT COUNT(*) AS count,
               MIN(sensor_value) AS minValue,
               MAX(sensor_value) AS maxValue,
               AVG(sensor_value) AS avgValue,
               SUM(action_triggered) AS triggers
        FROM sensor_records WHERE timestamp >= :since
        """
    )
    suspend fun statsSince(since: Long): PeriodStats

    /** Records not yet uploaded (synced = 0), oldest first. */
    @Query("SELECT * FROM sensor_records WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<SensorRecord>

    /** Marks a record as uploaded. */
    @Query("UPDATE sensor_records SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    /** Whether a record was uploaded (null if it no longer exists). */
    @Query("SELECT synced FROM sensor_records WHERE id = :id")
    suspend fun isSynced(id: Long): Boolean?

    /** Live count of records waiting for upload. */
    @Query("SELECT COUNT(*) FROM sensor_records WHERE synced = 0")
    fun observeUnsyncedCount(): Flow<Int>

    /** Deletes records older than [before]; returns the number deleted. */
    @Query("DELETE FROM sensor_records WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** Frees space when storage is full: deletes the [count] oldest records that are already in the cloud. */
    @Query("DELETE FROM sensor_records WHERE id IN (SELECT id FROM sensor_records WHERE synced = 1 ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldestSynced(count: Int): Int

    /** Clears the whole history. */
    @Query("DELETE FROM sensor_records")
    suspend fun deleteAll()
}

/** Result of statsSince (field names match the SQL aliases). */
data class PeriodStats(
    val count: Int,
    val minValue: Float?,
    val maxValue: Float?,
    val avgValue: Float?,
    val triggers: Int?
)
