package com.example.lab6_autorotatemanager.repository

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Репозиторій — одне місце для даних. ViewModel не знає ні про Retrofit, ні про Firebase, ні про Room:
 * вона викликає save() і отримує Result, а історію читає як Flow.
 *
 *   ViewModel ──► SensorRepository ─┬─► CloudApi  (Retrofit → Firebase REST, ЛР №3–4)
 *                                   └─► SensorDao (Room → SQLite auto_rotate.db, ЛР №5)
 *
 * Крок 2 методички: спершу пишемо в базу, потім відправляємо в хмару. Якщо мережі немає,
 * запис лишається в базі з synced = false і відправиться syncPending(), коли мережа повернеться
 * (навіть після перезапуску додатку). У порівнянні з ЛР №4 змінився лише репозиторій.
 *
 * Усі звернення до бази й мережі виконуються в Dispatchers.IO: Room забороняє запити
 * в головному потоці (IllegalStateException «Cannot access database on the main thread»).
 * Створюється один раз на весь додаток (AppGraph), тому всі екрани працюють з тими самими даними.
 */
class SensorRepository(
    private val api: CloudApi?,
    private val authToken: String?,
    private val deviceId: String,
    private val dao: SensorDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private val gson = Gson()

    /** Запити до хмари йдуть по одному — запис не відправиться двічі (save() і syncPending() одночасно). */
    private val uploadMutex = Mutex()

    val isCloudConfigured: Boolean get() = api != null

    /**
     * Зберегти вимір: 1) локально в Room (synced = false); 2) у хмару; успіх → synced = true.
     * Помилка бази — виняток (його обробляє ViewModel); помилка мережі — Result.failure,
     * а запис чекає в базі. Успіх — ключ запису у Firebase.
     */
    suspend fun save(reading: SensorReading): Result<String> = withContext(io) {
        val id = dao.insert(reading.toRecord())                    // 1. спершу локально
        uploadMutex.withLock {
            if (dao.isSynced(id) == true) return@withLock Result.success("—")   // уже відправив syncPending()
            push(reading.toPayload(deviceId))                      // 2. потім у хмару
                .onSuccess { dao.markSynced(id) }
        }
    }

    /** Відправити все, що не пішло, коли мережа повернулася. Повертає кількість відправлених записів. */
    suspend fun syncPending(): Int = withContext(io) {
        uploadMutex.withLock {
            var sent = 0
            for (record in dao.unsynced(SYNC_BATCH)) {
                if (push(record.toReading().toPayload(deviceId)).isFailure) break   // мережа знову зникла
                dao.markSynced(record.id)
                sent++
            }
            sent
        }
    }

    /** Скільки записів ще не відправлено в хмару (synced = false). */
    val pendingCount: Flow<Int> = dao.observeUnsyncedCount().flowOn(io)

    /** JSON, який піде в хмару (картка ЛР №3 показує його як «останній payload»). */
    fun payloadJson(reading: SensorReading): String = gson.toJson(reading.toPayload(deviceId))

    /** ЛР №4: записує фактичний стан актуаторів у status/{deviceId}. */
    suspend fun reportStatus(deviceId: String, report: ActuatorReport): Result<Unit> = call {
        val response = requireApi().putStatus(deviceId, report, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
    }

    // ------------------------------------------------------------ ЛР №5: історія з Room

    /** Останні [limit] записів для графіка; Room сам оновлює Flow після кожної зміни таблиці. */
    fun latest(limit: Int): Flow<List<SensorRecord>> = dao.observeLatest(limit).flowOn(io)

    /** Історія спрацювань системи безпеки. */
    fun triggered(limit: Int): Flow<List<SensorRecord>> = dao.observeTriggered(limit).flowOn(io)

    /** Кількість рядків у таблиці. */
    fun count(): Flow<Int> = dao.observeCount().flowOn(io)

    suspend fun statsSince(since: Long): PeriodStats = withContext(io) { dao.statsSince(since) }

    suspend fun deleteOlderThan(before: Long): Int = withContext(io) { dao.deleteOlderThan(before) }

    suspend fun clear() = withContext(io) { dao.deleteAll() }

    // ------------------------------------------------------------

    private suspend fun push(payload: SensorPayload): Result<String> = call {
        val response = requireApi().push(payload.deviceId, payload, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
        response.body()?.name ?: "—"
    }

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

    companion object {
        /** Скільки записів відправляти за один виклик syncPending(). */
        const val SYNC_BATCH = 500
    }
}
