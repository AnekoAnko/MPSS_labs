package com.example.lab7_autorotatemanager.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * ЛР №5: DAO (Data Access Object) — перелік операцій із таблицею sensor_records.
 * Room сам генерує реалізацію (SensorDao_Impl) на етапі компіляції через KSP
 * і перевіряє SQL-запити ще до запуску додатка.
 *
 *  • suspend-функції виконуються в корутині й не блокують UI-потік;
 *  • функції, що повертають Flow, — «живі» запити: Room повторно виконує їх
 *    після кожної зміни таблиці і віддає новий список (графік оновлюється сам).
 */
@Dao
interface SensorDao {

    /** Запис одного виміру; повертає id нового рядка. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SensorRecord): Long

    /**
     * Останні [limit] записів для графіка. Внутрішній SELECT бере найновіші,
     * зовнішній упорядковує їх за зростанням часу (зліва направо на осі X).
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM sensor_records ORDER BY timestamp DESC LIMIT :limit
        ) ORDER BY timestamp ASC
        """
    )
    fun observeLatest(limit: Int): Flow<List<SensorRecord>>

    /** Те саме одноразово (для перевірок і тестів). */
    @Query("SELECT * FROM sensor_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<SensorRecord>

    /** ЛР №7: останні [limit] записів, найновіші зверху (Dashboard). */
    @Query("SELECT * FROM sensor_records ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SensorRecord>>

    /** Історія спрацювань системи безпеки (найновіші зверху). */
    @Query("SELECT * FROM sensor_records WHERE action_triggered = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun observeTriggered(limit: Int): Flow<List<SensorRecord>>

    /** Загальна кількість рядків у таблиці. */
    @Query("SELECT COUNT(*) FROM sensor_records")
    fun observeCount(): Flow<Int>

    /** Агрегати за період (наприклад, за останню годину) — рахує сама SQLite. */
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

    /** Записи, які ще не відправлено в хмару (synced = false), від старих до нових. */
    @Query("SELECT * FROM sensor_records WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<SensorRecord>

    /** Позначити запис як відправлений у хмару. */
    @Query("UPDATE sensor_records SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    /** Чи відправлено запис (null — запису вже немає). */
    @Query("SELECT synced FROM sensor_records WHERE id = :id")
    suspend fun isSynced(id: Long): Boolean?

    /** Скільки записів чекає відправки — «живий» лічильник для картки хмари. */
    @Query("SELECT COUNT(*) FROM sensor_records WHERE synced = 0")
    fun observeUnsyncedCount(): Flow<Int>

    /** Ротація: видалити записи, старші за вказаний момент. Повертає кількість видалених. */
    @Query("DELETE FROM sensor_records WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /**
     * ЛР №7: аварійне звільнення місця, коли пам'ять заповнена (SQLiteFullException):
     * видаляємо [count] найстаріших записів, які ВЖЕ є в хмарі, — дані не втрачаються.
     */
    @Query("DELETE FROM sensor_records WHERE id IN (SELECT id FROM sensor_records WHERE synced = 1 ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldestSynced(count: Int): Int

    /** Повне очищення історії (кнопка на екрані «Аналітика»). */
    @Query("DELETE FROM sensor_records")
    suspend fun deleteAll()
}

/** Результат агрегатного запиту statsSince (поля = псевдоніми AS у SQL). */
data class PeriodStats(
    val count: Int,
    val minValue: Float?,
    val maxValue: Float?,
    val avgValue: Float?,
    val triggers: Int?
)
