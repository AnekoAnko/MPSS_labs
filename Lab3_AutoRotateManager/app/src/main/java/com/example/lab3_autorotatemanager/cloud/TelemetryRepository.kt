package com.example.lab3_autorotatemanager.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Repository — єдина точка доступу до хмари.
 * ViewModel не знає ні про Retrofit, ні про Firebase: вона отримує лише Result.
 */
class TelemetryRepository(
    private val api: FirebaseApi?,
    private val authToken: String?
) {

    val isConfigured: Boolean get() = api != null

    /** Відправляє один запис. Успіх — ключ запису у Firebase, помилка — виняток з описом. */
    suspend fun upload(data: SensorData): Result<String> = withContext(Dispatchers.IO) {
        try {
            val service = api ?: throw IllegalStateException("FIREBASE_DB_URL не задано в local.properties")
            val response = service.push(data.deviceId, data, authToken)
            if (!response.isSuccessful) {
                val details = response.errorBody()?.string()?.take(120).orEmpty()
                throw IOException("HTTP ${response.code()} $details".trim())
            }
            Result.success(response.body()?.name ?: "—")
        } catch (e: CancellationException) {
            throw e   // скасування корутини не є помилкою мережі
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
