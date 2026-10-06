package com.example.lab5_autorotatemanager.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Репозиторій — одне місце для даних. ViewModel не знає ні про Retrofit, ні про Firebase, ні про Room:
 * вона викликає suspend-функцію і отримує Result.
 *
 *   ViewModel ──► SensorRepository ─┬─► CloudApi  (Retrofit → Firebase REST, ЛР №3–4)
 *                                   └─► SensorDao (Room → SQLite auto_rotate.db, ЛР №5)
 *
 * Усі звернення до бази й мережі виконуються в Dispatchers.IO: Room забороняє запити
 * в головному потоці (IllegalStateException «Cannot access database on the main thread»).
 * Створюється один раз на весь додаток (AppGraph), тому всі екрани працюють з тими самими даними.
 */
class SensorRepository(
    private val api: CloudApi?,
    private val authToken: String?,
    private val dao: SensorDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    val isCloudConfigured: Boolean get() = api != null

    /** ЛР №3: відправляє один запис. Успіх — ключ запису у Firebase. */
    suspend fun upload(data: SensorData): Result<String> = call {
        val response = requireApi().push(data.deviceId, data, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
        response.body()?.name ?: "—"
    }

    /** ЛР №4: записує фактичний стан актуаторів у status/{deviceId}. */
    suspend fun reportStatus(deviceId: String, report: ActuatorReport): Result<Unit> = call {
        val response = requireApi().putStatus(deviceId, report, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
    }

    // ------------------------------------------------------------ ЛР №5: локальна БД Room

    /** Останні [limit] записів для графіка; Room сам оновлює Flow після кожної зміни таблиці. */
    fun latest(limit: Int): Flow<List<SensorRecord>> = dao.observeLatest(limit).flowOn(io)

    /** Історія спрацювань системи безпеки. */
    fun triggered(limit: Int): Flow<List<SensorRecord>> = dao.observeTriggered(limit).flowOn(io)

    /** Кількість рядків у таблиці. */
    fun count(): Flow<Int> = dao.observeCount().flowOn(io)

    suspend fun insert(record: SensorRecord): Long = withContext(io) { dao.insert(record) }

    suspend fun statsSince(since: Long): PeriodStats = withContext(io) { dao.statsSince(since) }

    suspend fun deleteOlderThan(before: Long): Int = withContext(io) { dao.deleteOlderThan(before) }

    suspend fun clear() = withContext(io) { dao.deleteAll() }

    // ------------------------------------------------------------

    /** Мережа — не в головному потоці; скасування корутини не вважаємо помилкою мережі. */
    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(io) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requireApi(): CloudApi =
        api ?: throw IllegalStateException("FIREBASE_DB_URL не задано в local.properties")

    private fun httpError(code: Int, body: String?) =
        IOException("HTTP $code ${body?.take(120).orEmpty()}".trim())
}
