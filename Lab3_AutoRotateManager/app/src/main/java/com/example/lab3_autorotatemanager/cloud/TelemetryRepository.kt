package com.example.lab3_autorotatemanager.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Single access point to the cloud. The ViewModel only gets a Result. */
class TelemetryRepository(
    private val api: FirebaseApi?,
    private val authToken: String?
) {

    val isConfigured: Boolean get() = api != null

    /** Uploads one record. Returns the Firebase key or an error. */
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
            throw e   // cancellation is not a network error
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
