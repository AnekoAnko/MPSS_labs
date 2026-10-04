package com.example.lab3_autorotatemanager.cloud

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab3_autorotatemanager.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** Статус хмарної синхронізації (колір індикатора визначається в UI). */
enum class SyncStatus(val label: String) {
    DISABLED("Вимкнено"),
    IDLE("Очікування даних"),
    SENDING("Надсилання…"),
    SUCCESS("Синхронізовано"),
    PENDING("Pending — немає мережі"),
    ERROR("Помилка запиту")
}

data class CloudUiState(
    val enabled: Boolean = true,
    val configured: Boolean = true,
    val online: Boolean = true,
    val status: SyncStatus = SyncStatus.IDLE,
    val deviceId: String = "",
    val sentCount: Int = 0,
    val pendingCount: Int = 0,
    val lastSuccessTime: String? = null,
    val lastRecordKey: String? = null,
    val lastError: String? = null,
    val lastPayloadJson: String? = null,
    val recent: List<String> = emptyList()
)

/**
 * ViewModel хмарної синхронізації: отримує виміри від Activity, вирішує, коли їх відправляти
 * (SendPolicy), веде чергу Pending при відсутності мережі й передає дані в Repository.
 */
class CloudSyncViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: TelemetryRepository = TelemetryRepository(
        api = CloudModule.createApi(BuildConfig.FIREBASE_DB_URL),
        authToken = BuildConfig.FIREBASE_AUTH.ifBlank { null }
    ),
    private val monitor: ConnectivityMonitor = ConnectivityMonitor(app),
    private val deviceId: String = deviceIdOf(app)
) : AndroidViewModel(app) {

    private val policy = SendPolicy()
    private val gson = Gson()
    private val sendMutex = Mutex()                 // запити йдуть по одному
    private val pending = ArrayDeque<SensorData>()  // записи зі статусом Pending
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var latest: SensorData? = null

    private val _uiState = MutableStateFlow(
        CloudUiState(configured = repository.isConfigured, online = monitor.isOnline.value, deviceId = deviceId)
    )
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    /** Одноразові повідомлення для Toast. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        monitor.start()
        if (!repository.isConfigured) {
            log("FIREBASE_DB_URL не задано — додайте його в local.properties")
            _uiState.update { it.copy(status = SyncStatus.ERROR, lastError = "Не задано FIREBASE_DB_URL") }
        }

        // Реакція на зміну стану мережі
        viewModelScope.launch {
            var wasOnline = monitor.isOnline.value
            monitor.isOnline.collect { online ->
                _uiState.update { it.copy(online = online) }
                if (wasOnline && !online) {
                    log("Мережа зникла — нові записи отримують статус Pending")
                    _events.tryEmit("Немає інтернету: дані зберігаються зі статусом Pending")
                    if (_uiState.value.enabled) _uiState.update { it.copy(status = SyncStatus.PENDING) }
                }
                if (!wasOnline && online) {
                    log("Мережа відновлена")
                    launch { sendMutex.withLock { flushPending(announce = true) } }
                }
                wasOnline = online
            }
        }

        // Періодична відправка: кожні 5 с, якщо є нові дані
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                val sample = latest ?: continue
                if (_uiState.value.enabled && policy.intervalElapsed(sample, now())) {
                    enqueue(sample, reason = "інтервал 5 с")
                }
            }
        }
    }

    /** Виклик з Activity на кожну подію акселерометра (після фільтра ЛР №1). */
    fun onSample(x: Float, y: Float, z: Float, orientation: String) {
        val sample = SensorData(
            deviceId = deviceId,
            type = "ACCELEROMETER",
            x = x.round2(), y = y.round2(), z = z.round2(),
            value = sqrt(x * x + y * y + z * z).round2(),
            orientation = orientation
        )
        latest = sample
        if (_uiState.value.enabled && policy.deltaExceeded(sample, now())) {
            enqueue(sample, reason = "зміна > Δ ${policy.delta} м/с²")
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(enabled = enabled, status = if (enabled) SyncStatus.IDLE else SyncStatus.DISABLED)
        }
        log(if (enabled) "Синхронізацію увімкнено" else "Синхронізацію вимкнено")
    }

    fun sendNow() {
        latest?.let { enqueue(it.copy(timestamp = now()), reason = "вручну") }
            ?: _events.tryEmit("Ще немає даних з акселерометра")
    }

    // ------------------------------------------------------------------ відправка

    private fun enqueue(sample: SensorData, reason: String) {
        policy.markSent(sample, now())
        viewModelScope.launch { sendMutex.withLock { deliver(sample, reason) } }
    }

    private suspend fun deliver(sample: SensorData, reason: String) {
        _uiState.update { it.copy(lastPayloadJson = gson.toJson(sample)) }

        if (!monitor.isOnline.value) {
            addPending(sample)
            _uiState.update { it.copy(status = SyncStatus.PENDING) }
            log("Pending ($reason): немає мережі, у черзі ${pending.size}")
            return
        }
        if (pending.isNotEmpty() && !flushPending(announce = false)) {
            addPending(sample)
            return
        }

        _uiState.update { it.copy(status = SyncStatus.SENDING) }
        repository.upload(sample)
            .onSuccess { key -> onUploaded(key, reason) }
            .onFailure { e -> onUploadFailed(sample, e) }
    }

    /** Відправляє накопичені записи. Повертає true, якщо черга спорожніла. */
    private suspend fun flushPending(announce: Boolean): Boolean {
        if (pending.isEmpty()) return true
        var sent = 0
        log("Відправка черги Pending: ${pending.size} записів")
        while (pending.isNotEmpty()) {
            if (!monitor.isOnline.value) break
            val item = pending.first()
            _uiState.update { it.copy(status = SyncStatus.SENDING) }
            val result = repository.upload(item)
            if (result.isSuccess) {
                pending.removeFirst()
                sent++
                onUploaded(result.getOrNull() ?: "—", "з черги Pending")
            } else {
                onUploadFailed(null, result.exceptionOrNull())
                break
            }
        }
        _uiState.update { it.copy(pendingCount = pending.size) }
        if (announce && sent > 0) _events.tryEmit("Мережа відновлена: надіслано $sent записів з черги")
        return pending.isEmpty()
    }

    private fun onUploaded(key: String, reason: String) {
        _uiState.update {
            it.copy(
                status = if (pending.isEmpty()) SyncStatus.SUCCESS else SyncStatus.PENDING,
                sentCount = it.sentCount + 1,
                lastSuccessTime = timeFormat.format(Date()),
                lastRecordKey = key,
                lastError = null,
                pendingCount = pending.size
            )
        }
        log("✓ 200 OK ($reason) → key $key")
    }

    private fun onUploadFailed(sample: SensorData?, e: Throwable?) {
        sample?.let { addPending(it) }
        val message = e?.message ?: e?.javaClass?.simpleName ?: "невідома помилка"
        _uiState.update { it.copy(status = SyncStatus.ERROR, lastError = message, pendingCount = pending.size) }
        log("✗ Помилка: $message; у черзі ${pending.size}")
    }

    private fun addPending(sample: SensorData) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()   // найстаріший запис відкидаємо
        pending.addLast(sample)
        _uiState.update { it.copy(pendingCount = pending.size) }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${timeFormat.format(Date())}  $message"
        _uiState.update { it.copy(recent = (it.recent + line).takeLast(6)) }
    }

    private fun now() = System.currentTimeMillis()

    private fun Float.round2(): Float = Math.round(this * 100f) / 100f

    override fun onCleared() {
        monitor.stop()
    }

    companion object {
        const val TAG = "CloudSync"
        const val MAX_PENDING = 200
    }
}
