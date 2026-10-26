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
 * Single source of data. ViewModels call save() and get a Result; they don't know about Retrofit.
 * If upload fails (no network), the record stays in the in-memory pending queue
 * and syncPending() sends it when the network is back.
 */
class SensorRepository(
    private val api: CloudApi?,
    private val authToken: String?,
    private val deviceId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private val gson = Gson()

    /** Records not yet uploaded (pending). */
    private val pending = ArrayDeque<SensorPayload>()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** Uploads run one at a time, so the queue is never sent twice. */
    private val uploadMutex = Mutex()

    val isCloudConfigured: Boolean get() = api != null

    /** Uploads a sample. Returns the Firebase key on success. */
    suspend fun save(reading: SensorReading): Result<String> = uploadMutex.withLock {
        val payload = reading.toPayload(deviceId)
        push(payload).onFailure { addPending(payload) }
    }

    /** Uploads everything left in the queue. Returns the number of records sent. */
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

    /** JSON that will be sent to the cloud. */
    fun payloadJson(reading: SensorReading): String = gson.toJson(reading.toPayload(deviceId))

    /** Writes the actual actuator state to status/{deviceId}. */
    suspend fun reportStatus(deviceId: String, report: ActuatorReport): Result<Unit> = call {
        val response = requireApi().putStatus(deviceId, report, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
    }

    private suspend fun push(payload: SensorPayload): Result<String> = call {
        val response = requireApi().push(payload.deviceId, payload, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
        response.body()?.name ?: "—"
    }

    private fun addPending(payload: SensorPayload) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()   // drop the oldest record
        pending.addLast(payload)
        _pendingCount.value = pending.size
    }

    /** Runs network calls on IO; cancellation is not treated as a network error. */
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
