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
 * Single source of data. ViewModels call save()/syncPending() and read history as Flow;
 * they don't know about Retrofit or Room. Offline-first: AnalyticsViewModel saves to Room,
 * CloudViewModel uploads the queue with syncPending(). All DB and network work runs on Dispatchers.IO.
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
    private var insertsSinceCleanup = 0

    val isCloudConfigured: Boolean get() = api != null

    private val _storageFull = MutableStateFlow(false)
    /** Storage is full: records go straight to the cloud. */
    val storageFull: StateFlow<Boolean> = _storageFull.asStateFlow()

    /**
     * Stores a sample in Room (synced = false); CloudViewModel uploads it later via syncPending().
     * Every [CLEANUP_EVERY] inserts, records older than [RETENTION_MS] are deleted.
     * If storage is full and nothing can be freed, the sample goes straight to the cloud.
     * Returns Result.failure on DB errors (and on upload errors in the storage-full case).
     */
    suspend fun save(reading: SensorReading): Result<Unit> = call {
        if (insertOrNull(reading.toRecord()) == null) {
            uploadMutex.withLock { push(reading.toPayload(deviceId)).getOrThrow() }   // disk full → cloud only
        } else if (++insertsSinceCleanup >= CLEANUP_EVERY) {
            insertsSinceCleanup = 0
            val removed = dao.deleteOlderThan(reading.timestamp - RETENTION_MS)
            if (removed > 0) Log.d(TAG, "Ротація: видалено $removed старих записів")
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

    /**
     * Uploads queued records oldest first and marks each one synced.
     * Stops at the first failure; the rest stay in the queue.
     */
    suspend fun syncPending(): SyncResult = withContext(io) {
        uploadMutex.withLock {
            var result = SyncResult()
            val queue = call { dao.unsynced(SYNC_BATCH) }
                .getOrElse { return@withLock result.copy(error = it) }
            for (record in queue) {
                val payload = record.toReading().toPayload(deviceId)
                val key = call { push(payload).getOrThrow().also { dao.markSynced(record.id) } }
                    .getOrElse { return@withLock result.copy(error = it) }
                result = result.copy(sent = result.sent + 1, lastKey = key, lastPayloadJson = gson.toJson(payload))
            }
            result
        }
    }

    /** Number of records not yet uploaded. */
    val pendingCount: Flow<Int> = dao.observeUnsyncedCount().flowOn(io)

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

    suspend fun clear(): Result<Unit> = call { dao.deleteAll() }

    private suspend fun push(payload: SensorPayload): Result<String> = call {
        val response = requireApi().push(payload.deviceId, payload, authToken)
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
        response.body()?.name ?: "—"
    }

    /** Runs DB/network work on IO and wraps exceptions in Result; cancellation is rethrown. */
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
        /** Run retention cleanup every N inserts. */
        const val CLEANUP_EVERY = 100
        /** Keep records for 24 hours. */
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        const val TAG = "SensorRepo"
    }
}

/** Result of one syncPending() run. */
data class SyncResult(
    val sent: Int = 0,
    val lastKey: String? = null,
    val lastPayloadJson: String? = null,
    /** First upload error; null if the whole queue was sent. */
    val error: Throwable? = null
)
