package com.example.lab7_autorotatemanager.repository

import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Single source of data. ViewModels call save() and read history as Flow; they don't know about Retrofit or Room.
 * save() writes to Room first, then uploads; without network the record stays with synced = false
 * and syncPending() sends it later. All DB and network work runs on Dispatchers.IO.
 */
class SensorRepository(
    private val api: CloudApi?,
    private val authToken: String?,
    private val deviceId: String,
    private val dao: SensorDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    private val gson = Gson()

    /** Uploads run one at a time, so a record is never sent twice. */
    private val uploadMutex = Mutex()

    val isCloudConfigured: Boolean get() = api != null

    private val _storageFull = MutableStateFlow(false)
    /** Storage is full: records go straight to the cloud. */
    val storageFull: StateFlow<Boolean> = _storageFull.asStateFlow()

    /**
     * Saves a sample: 1) to Room (synced = false), 2) to the cloud, then marks it synced.
     * DB errors throw; network errors return Result.failure and the record waits in the queue.
     */
    suspend fun save(reading: SensorReading): Result<String> = withContext(io) {
        val id = insertOrNull(reading.toRecord())                  // 1. local first
            ?: return@withContext uploadMutex.withLock { push(reading.toPayload(deviceId)) }   // disk full → cloud only
        uploadMutex.withLock {
            if (dao.isSynced(id) == true) return@withLock Result.success("—")   // already sent by syncPending()
            push(reading.toPayload(deviceId))                      // 2. then upload
                .onSuccess { dao.markSynced(id) }
        }
    }

    /**
     * Handles SQLiteFullException: deletes the oldest already-uploaded records and retries.
     * Returns null if nothing could be freed, so save() uploads without a local copy.
     */
    private suspend fun insertOrNull(record: SensorRecord): Long? {
        try {
            return dao.insert(record).also { if (_storageFull.value) _storageFull.value = false }
        } catch (e: SQLiteFullException) {
            Log.w(TAG, "Диск заповнено: ${e.message}; звільняю місце")
        }
        val freed = try { dao.deleteOldestSynced(EMERGENCY_DELETE) } catch (e: SQLiteFullException) { 0 }
        if (freed > 0) {
            try {
                Log.w(TAG, "Видалено $freed найстаріших записів, які вже є в хмарі")
                return dao.insert(record)
            } catch (e: SQLiteFullException) {
                Log.e(TAG, "Місця все одно не вистачає")
            }
        }
        _storageFull.value = true
        return null
    }

    /** Uploads everything left in the queue. Returns the number of records sent. */
    suspend fun syncPending(): Int = withContext(io) {
        uploadMutex.withLock {
            var sent = 0
            for (record in dao.unsynced(SYNC_BATCH)) {
                if (push(record.toReading().toPayload(deviceId)).isFailure) break   // network lost again
                dao.markSynced(record.id)
                sent++
            }
            sent
        }
    }

    /** Number of records not yet uploaded. */
    val pendingCount: Flow<Int> = dao.observeUnsyncedCount().flowOn(io)

    /** JSON that will be sent to the cloud. */
    fun payloadJson(reading: SensorReading): String = gson.toJson(reading.toPayload(deviceId))

    /** Writes the actual actuator state to status/{deviceId}. */
    suspend fun reportStatus(deviceId: String, report: ActuatorReport): Result<Unit> = call {
        val response = requireApi().putStatus(deviceId, report, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
    }

    /** Last [limit] records for the chart; re-emits after every change. */
    fun latest(limit: Int): Flow<List<SensorRecord>> = dao.observeLatest(limit).flowOn(io)

    /** Last [limit] records, newest first (dashboard). */
    fun recent(limit: Int): Flow<List<SensorRecord>> = dao.observeRecent(limit).flowOn(io)

    /** History of alarm triggers. */
    fun triggered(limit: Int): Flow<List<SensorRecord>> = dao.observeTriggered(limit).flowOn(io)

    /** Row count. */
    fun count(): Flow<Int> = dao.observeCount().flowOn(io)

    suspend fun statsSince(since: Long): PeriodStats = withContext(io) { dao.statsSince(since) }

    suspend fun deleteOlderThan(before: Long): Int = withContext(io) { dao.deleteOlderThan(before) }

    suspend fun clear() = withContext(io) { dao.deleteAll() }

    private suspend fun push(payload: SensorPayload): Result<String> = call {
        val response = requireApi().push(payload.deviceId, payload, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
        response.body()?.name ?: "—"
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
        /** Max records per syncPending() call. */
        const val SYNC_BATCH = 500
        /** How many oldest uploaded records to delete when storage is full. */
        const val EMERGENCY_DELETE = 1_000
        const val TAG = "SensorRepo"
    }
}
