package com.example.lab4_autorotatemanager.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Репозиторій — одне місце для даних. ViewModel не знає ні про Retrofit, ні про Firebase:
 * вона викликає suspend-функцію і отримує Result.
 *
 *   ViewModel ──► SensorRepository ──► CloudApi (Retrofit → Firebase REST)
 *
 * Створюється один раз на весь додаток (AppGraph), тому всі екрани працюють з тими самими даними.
 */
class SensorRepository(
    private val api: CloudApi?,
    private val authToken: String?,
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
