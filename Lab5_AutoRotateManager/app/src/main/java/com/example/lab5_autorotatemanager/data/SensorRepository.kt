package com.example.lab5_autorotatemanager.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * ЛР №5: Repository — єдина точка доступу до даних для ViewModel.
 * ViewModel не знає, що під ним Room/SQLite: її можна протестувати з фейковим DAO.
 *
 * Усі звернення до бази виконуються в Dispatchers.IO (пул потоків для вводу-виводу):
 * Room забороняє запити в головному (UI) потоці — інакше IllegalStateException
 * «Cannot access database on the main thread».
 */
class SensorRepository(
    private val dao: SensorDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    /** Потік останніх точок для графіка. */
    fun latest(limit: Int): Flow<List<SensorRecord>> = dao.observeLatest(limit).flowOn(io)

    /** Потік історії спрацювань. */
    fun triggered(limit: Int): Flow<List<SensorRecord>> = dao.observeTriggered(limit).flowOn(io)

    /** Потік кількості рядків у таблиці. */
    fun count(): Flow<Int> = dao.observeCount().flowOn(io)

    suspend fun insert(record: SensorRecord): Long = withContext(io) { dao.insert(record) }

    suspend fun statsSince(since: Long): PeriodStats = withContext(io) { dao.statsSince(since) }

    suspend fun deleteOlderThan(before: Long): Int = withContext(io) { dao.deleteOlderThan(before) }

    suspend fun clear() = withContext(io) { dao.deleteAll() }

    companion object {
        fun from(context: Context): SensorRepository =
            SensorRepository(AppDatabase.getInstance(context).sensorDao())
    }
}
