package com.example.lab4_autorotatemanager.cloud

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab4_autorotatemanager.AppGraph
import com.example.lab4_autorotatemanager.helpers.ConnectivityMonitor
import com.example.lab4_autorotatemanager.helpers.SensorHub
import com.example.lab4_autorotatemanager.repository.SensorData
import com.example.lab4_autorotatemanager.repository.SensorRepository
import com.example.lab4_autorotatemanager.repository.SystemStateRepository
import com.google.gson.Gson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * ЛР №3: хмарна синхронізація. Отримує виміри з SensorHub, вирішує, коли їх відправляти
 * (SendPolicy), веде чергу Pending при відсутності мережі й передає дані в SensorRepository.
 */
class CloudViewModel(
    private val repository: SensorRepository = AppGraph.sensorRepository,
    private val monitor: ConnectivityMonitor = ConnectivityMonitor(AppGraph.app),
    private val hub: SensorHub = AppGraph.sensorHub,
    private val system: SystemStateRepository = AppGraph.systemState,
    private val deviceId: String = AppGraph.deviceId
) : ViewModel() {

    private val policy = SendPolicy()
    private val gson = Gson()
    private val sendMutex = Mutex()                 // запити йдуть по одному
    private val pending = ArrayDeque<SensorData>()  // записи зі статусом Pending
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var latest: SensorData? = null

    private val _state = MutableStateFlow(
        CloudState(configured = repository.isCloudConfigured, online = monitor.isOnline.value, deviceId = deviceId)
    )
    val state: StateFlow<CloudState> = _state.asStateFlow()

    private val _events = Channel<CloudEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        monitor.start()
        if (!repository.isCloudConfigured) {
            log("FIREBASE_DB_URL не задано — додайте його в local.properties")
            _state.update { it.copy(status = SyncStatus.ERROR, lastError = "Не задано FIREBASE_DB_URL") }
        }

        // Дані акселерометра (після фільтра ЛР №1)
        viewModelScope.launch {
            hub.filtered.collect { s -> onSample(s.x, s.y, s.z, system.orientation.value) }
        }

        // Реакція на зміну стану мережі
        viewModelScope.launch {
            var wasOnline = monitor.isOnline.value
            monitor.isOnline.collect { online ->
                _state.update { it.copy(online = online) }
                if (wasOnline && !online) {
                    log("Мережа зникла — нові записи отримують статус Pending")
                    _events.send(CloudEvent.ShowMessage("Немає інтернету: дані зберігаються зі статусом Pending"))
                    if (_state.value.enabled) _state.update { it.copy(status = SyncStatus.PENDING) }
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
                if (_state.value.enabled && policy.intervalElapsed(sample, now())) {
                    enqueue(sample, reason = "інтервал 5 с")
                }
            }
        }
    }

    // ЄДИНИЙ вхід для дій користувача
    fun onAction(action: CloudAction) {
        when (action) {
            is CloudAction.SetEnabled -> setEnabled(action.enabled)
            CloudAction.SendNow -> sendNow()
        }
    }

    private fun onSample(x: Float, y: Float, z: Float, orientation: String) {
        val sample = SensorData(
            deviceId = deviceId,
            type = "ACCELEROMETER",
            x = x.round2(), y = y.round2(), z = z.round2(),
            value = sqrt(x * x + y * y + z * z).round2(),
            orientation = orientation
        )
        latest = sample
        if (_state.value.enabled && policy.deltaExceeded(sample, now())) {
            enqueue(sample, reason = "зміна > Δ ${policy.delta} м/с²")
        }
    }

    private fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled, status = if (enabled) SyncStatus.IDLE else SyncStatus.DISABLED) }
        log(if (enabled) "Синхронізацію увімкнено" else "Синхронізацію вимкнено")
    }

    private fun sendNow() {
        val sample = latest
        if (sample == null) _events.trySend(CloudEvent.ShowMessage("Ще немає даних з акселерометра"))
        else enqueue(sample.copy(timestamp = now()), reason = "вручну")
    }

    // ------------------------------------------------------------------ відправка

    private fun enqueue(sample: SensorData, reason: String) {
        policy.markSent(sample, now())
        viewModelScope.launch { sendMutex.withLock { deliver(sample, reason) } }
    }

    private suspend fun deliver(sample: SensorData, reason: String) {
        _state.update { it.copy(lastPayloadJson = gson.toJson(sample)) }

        if (!monitor.isOnline.value) {
            addPending(sample)
            _state.update { it.copy(status = SyncStatus.PENDING) }
            log("Pending ($reason): немає мережі, у черзі ${pending.size}")
            return
        }
        if (pending.isNotEmpty() && !flushPending(announce = false)) {
            addPending(sample)
            return
        }

        _state.update { it.copy(status = SyncStatus.SENDING) }
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
            _state.update { it.copy(status = SyncStatus.SENDING) }
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
        _state.update { it.copy(pendingCount = pending.size) }
        if (announce && sent > 0) _events.send(CloudEvent.ShowMessage("Мережа відновлена: надіслано $sent записів з черги"))
        return pending.isEmpty()
    }

    private fun onUploaded(key: String, reason: String) {
        _state.update {
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
        _state.update { it.copy(status = SyncStatus.ERROR, lastError = message, pendingCount = pending.size) }
        log("✗ Помилка: $message; у черзі ${pending.size}")
    }

    private fun addPending(sample: SensorData) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()   // найстаріший запис відкидаємо
        pending.addLast(sample)
        _state.update { it.copy(pendingCount = pending.size) }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${timeFormat.format(Date())}  $message"
        _state.update { it.copy(recent = (it.recent + line).takeLast(6)) }
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
