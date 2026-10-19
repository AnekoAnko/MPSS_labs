package com.example.lab4_autorotatemanager.cloud

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab4_autorotatemanager.AppGraph
import com.example.lab4_autorotatemanager.control.DecisionEngine
import com.example.lab4_autorotatemanager.helpers.ConnectivityMonitor
import com.example.lab4_autorotatemanager.helpers.SensorHub
import com.example.lab4_autorotatemanager.repository.SensorReading
import com.example.lab4_autorotatemanager.repository.SensorRepository
import com.example.lab4_autorotatemanager.repository.SystemStateRepository
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

/**
 * ЛР №3: хмарна синхронізація. Отримує виміри з SensorHub, вирішує, коли їх відправляти
 * (SendPolicy), і викликає repository.save(). Куди і як писати (хмара, черга Pending,
 * Room) вирішує SensorRepository — ViewModel лише перетворює Result на State та Event.
 */
class CloudViewModel(
    private val repository: SensorRepository = AppGraph.sensorRepository,
    private val monitor: ConnectivityMonitor = ConnectivityMonitor(AppGraph.app),
    private val hub: SensorHub = AppGraph.sensorHub,
    private val system: SystemStateRepository = AppGraph.systemState,
    private val deviceId: String = AppGraph.deviceId
) : ViewModel() {

    private val policy = SendPolicy()
    private val sendMutex = Mutex()                 // відправки йдуть по одному
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var latest: SensorReading? = null

    private val _state = MutableStateFlow(
        CloudState(configured = repository.isCloudConfigured, online = monitor.isOnline.value, deviceId = deviceId)
    )
    val state: StateFlow<CloudState> = _state.asStateFlow()

    private val _events = Channel<CloudEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        if (!repository.isCloudConfigured) {
            log("FIREBASE_DB_URL не задано — додайте його в local.properties")
            _state.update { it.copy(status = SyncStatus.ERROR, lastError = "Не задано FIREBASE_DB_URL") }
        }

        // Дані акселерометра (після фільтра ЛР №1)
        viewModelScope.launch {
            hub.filtered.collect { s -> onSample(s.x, s.y, s.z, system.orientation.value) }
        }

        // Скільки записів чекає відправки — рахує репозиторій
        viewModelScope.launch {
            repository.pendingCount.collect { n -> _state.update { it.copy(pendingCount = n) } }
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
                    if (_state.value.enabled) _state.update { it.copy(status = SyncStatus.IDLE) }
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

    // ЄДИНИЙ вхід у ViewModel — всі дії приходять сюди
    fun onAction(action: CloudAction) {
        when (action) {
            CloudAction.Start -> monitor.start()   // Activity видно → стежимо за мережею
            CloudAction.Stop -> monitor.stop()     // додаток згорнуто → знімаємо NetworkCallback
            is CloudAction.SetEnabled -> setEnabled(action.enabled)
            CloudAction.SendNow -> sendNow()
        }
    }

    private fun onSample(x: Float, y: Float, z: Float, orientation: String) {
        val sample = SensorReading(
            x = x, y = y, z = z,
            tilt = DecisionEngine.tiltDegrees(x, y, z),
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

    private fun enqueue(sample: SensorReading, reason: String) {
        policy.markSent(sample, now())
        viewModelScope.launch { sendMutex.withLock { deliver(sample, reason) } }
    }

    private suspend fun deliver(sample: SensorReading, reason: String) {
        val online = monitor.isOnline.value
        _state.update {
            it.copy(
                lastPayloadJson = repository.payloadJson(sample),
                status = if (online) SyncStatus.SENDING else SyncStatus.PENDING
            )
        }
        // Спершу те, що накопичилось, — щоб записи йшли в хмару в порядку вимірів
        if (online && _state.value.pendingCount > 0) flushPending(announce = false)

        repository.save(sample)
            .onSuccess { key -> onUploaded(key, reason) }
            .onFailure { e ->
                if (!monitor.isOnline.value) {
                    _state.update { it.copy(status = SyncStatus.PENDING) }
                    log("Pending ($reason): немає мережі, запис чекає відправки")
                } else {
                    onUploadFailed(e)
                }
            }
    }

    /** Відправляє накопичені записи через репозиторій. */
    private suspend fun flushPending(announce: Boolean) {
        if (_state.value.pendingCount == 0) return
        log("Відправка черги Pending: ${_state.value.pendingCount} записів")
        val sent = repository.syncPending()
        if (sent > 0) {
            _state.update {
                it.copy(status = SyncStatus.SUCCESS, sentCount = it.sentCount + sent, lastSuccessTime = timeFormat.format(Date()), lastError = null)
            }
            log("✓ з черги Pending надіслано $sent записів")
            if (announce) _events.send(CloudEvent.ShowMessage("Мережа відновлена: надіслано $sent записів з черги"))
        }
    }

    private fun onUploaded(key: String, reason: String) {
        _state.update {
            it.copy(
                status = SyncStatus.SUCCESS,   // скільки ще в черзі — окремо, у pendingCount
                sentCount = it.sentCount + 1,
                lastSuccessTime = timeFormat.format(Date()),
                lastRecordKey = key,
                lastError = null
            )
        }
        log("✓ 200 OK ($reason) → key $key")
    }

    private fun onUploadFailed(e: Throwable) {
        val message = e.message ?: e.javaClass.simpleName
        _state.update { it.copy(status = SyncStatus.ERROR, lastError = message) }
        log("✗ Помилка: $message; запис чекає в черзі Pending")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${timeFormat.format(Date())}  $message"
        _state.update { it.copy(recent = (it.recent + line).takeLast(6)) }
    }

    private fun now() = System.currentTimeMillis()

    override fun onCleared() {
        monitor.stop()
    }

    companion object {
        const val TAG = "CloudSync"
    }
}
