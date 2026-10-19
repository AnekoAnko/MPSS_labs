package com.example.lab4_autorotatemanager.repository

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Репозиторій — одне місце для даних. ViewModel не знає ні про Retrofit, ні про Firebase:
 * вона викликає suspend-функцію save() і отримує Result.
 *
 *   ViewModel ──► SensorRepository ──► CloudApi (Retrofit → Firebase REST)
 *
 * Крок 1 методички — тільки хмара. Якщо відправити не вдалося (немає мережі), запис лишається
 * в черзі Pending у пам'яті й відправляється syncPending(), коли мережа повернеться.
 * У ЛР №5 цю чергу замінить таблиця Room (synced = false) — ViewModel і екран при цьому не зміняться.
 *
 * Створюється один раз на весь додаток (AppGraph), тому всі екрани працюють з тими самими даними.
 */
class SensorRepository(
    private val api: CloudApi?,
    private val authToken: String?,
    private val deviceId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private val gson = Gson()

    /** Записи, які ще не дійшли до хмари (статус Pending). */
    private val pending = ArrayDeque<SensorPayload>()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** Запити до хмари йдуть по одному — черга не відправиться двічі. */
    private val uploadMutex = Mutex()

    val isCloudConfigured: Boolean get() = api != null

    /** ЛР №3: зберегти вимір — відправити в хмару. Успіх — ключ запису у Firebase. */
    suspend fun save(reading: SensorReading): Result<String> = uploadMutex.withLock {
        val payload = reading.toPayload(deviceId)
        push(payload).onFailure { addPending(payload) }
    }

    /** Відправити все, що не пішло, коли мережа повернулася. Повертає кількість відправлених записів. */
    suspend fun syncPending(): Int = uploadMutex.withLock {
        var sent = 0
        while (pending.isNotEmpty()) {
            if (push(pending.first()).isFailure) break
            pending.removeFirst()
            sent++
        }
        _pendingCount.value = pending.size
        sent
    }

    /** JSON, який піде в хмару (картка показує його як «останній payload»). */
    fun payloadJson(reading: SensorReading): String = gson.toJson(reading.toPayload(deviceId))

    /** ЛР №4: записує фактичний стан актуаторів у status/{deviceId}. */
    suspend fun reportStatus(deviceId: String, report: ActuatorReport): Result<Unit> = call {
        val response = requireApi().putStatus(deviceId, report, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
    }

    // ------------------------------------------------------------

    private suspend fun push(payload: SensorPayload): Result<String> = call {
        val response = requireApi().push(payload.deviceId, payload, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
        response.body()?.name ?: "—"
    }

    private fun addPending(payload: SensorPayload) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()   // найстаріший запис відкидаємо
        pending.addLast(payload)
        _pendingCount.value = pending.size
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
        const val MAX_PENDING = 200
    }
}
