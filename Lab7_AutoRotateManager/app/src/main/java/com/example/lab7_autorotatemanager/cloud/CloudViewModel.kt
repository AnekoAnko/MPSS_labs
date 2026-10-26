package com.example.lab7_autorotatemanager.cloud

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab7_autorotatemanager.helpers.ConnectivityMonitor
import com.example.lab7_autorotatemanager.repository.CloudLink
import com.example.lab7_autorotatemanager.repository.SensorRepository
import com.example.lab7_autorotatemanager.repository.SystemStateRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud sync: the only uploader. AnalyticsViewModel stores records in Room (synced = false);
 * this class sends the queue whenever it grows and the network and sync are on.
 * Offline, records simply wait in Room and are sent when the network returns.
 */
class CloudViewModel(
    private val repository: SensorRepository,
    private val monitor: ConnectivityMonitor,
    private val system: SystemStateRepository,
    private val deviceId: String
) : ViewModel() {

    private val sendMutex = Mutex()                 // one upload run at a time
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

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

        // Cloud status → dashboard
        viewModelScope.launch {
            _state.collect { s ->
                system.setCloud(
                    CloudLink(
                        configured = s.configured, online = s.online, status = s.status.label,
                        pending = s.pendingCount, lastSync = s.lastSuccessTime
                    )
                )
            }
        }

        // Pending count from the repository
        viewModelScope.launch {
            repository.pendingCount.collect { n -> _state.update { it.copy(pendingCount = n) } }
        }

        // React to network changes
        viewModelScope.launch {
            var wasOnline = monitor.isOnline.value
            monitor.isOnline.collect { online ->
                _state.update { it.copy(online = online) }
                if (wasOnline && !online) {
                    log("Мережа зникла — нові записи чекають у черзі Pending")
                    _events.send(CloudEvent.ShowMessage("Немає інтернету: дані зберігаються зі статусом Pending"))
                    if (_state.value.enabled) _state.update { it.copy(status = SyncStatus.PENDING) }
                }
                if (!wasOnline && online) {
                    log("Мережа відновлена")
                    if (_state.value.enabled) _state.update { it.copy(status = SyncStatus.IDLE) }
                }
                wasOnline = online
            }
        }

        // Auto-sync: new records in the queue + network + sync enabled → upload
        viewModelScope.launch {
            combine(
                repository.pendingCount,
                monitor.isOnline,
                _state.map { it.enabled }.distinctUntilChanged()
            ) { pending, online, enabled -> pending > 0 && online && enabled && repository.isCloudConfigured }
                .conflate()
                .collect { ready -> if (ready) flush(reason = "авто") }
        }
    }

    // Single entry point for UI actions
    fun onAction(action: CloudAction) {
        when (action) {
            CloudAction.Start -> monitor.start()   // app visible: watch the network
            CloudAction.Stop -> monitor.stop()     // app hidden: unregister the callback
            is CloudAction.SetEnabled -> setEnabled(action.enabled)
            CloudAction.SendNow -> sendNow()
        }
    }

    private fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled, status = if (enabled) SyncStatus.IDLE else SyncStatus.DISABLED) }
        log(if (enabled) "Синхронізацію увімкнено" else "Синхронізацію вимкнено")
    }

    private fun sendNow() {
        when {
            !monitor.isOnline.value -> _events.trySend(CloudEvent.ShowMessage("Немає мережі: записи чекають у черзі"))
            _state.value.pendingCount == 0 -> _events.trySend(CloudEvent.ShowMessage("Черга порожня — усе вже в хмарі"))
            else -> viewModelScope.launch { flush(reason = "вручну") }
        }
    }

    /** Uploads the queue and maps the result to state. */
    private suspend fun flush(reason: String): Unit = sendMutex.withLock {
        _state.update { it.copy(status = SyncStatus.SENDING) }
        val result = repository.syncPending()
        if (result.sent > 0) {
            _state.update {
                it.copy(
                    status = SyncStatus.SUCCESS,
                    sentCount = it.sentCount + result.sent,
                    lastSuccessTime = timeFormat.format(Date()),
                    lastRecordKey = result.lastKey,
                    lastPayloadJson = result.lastPayloadJson,
                    lastError = null
                )
            }
            log("✓ 200 OK ($reason): надіслано ${result.sent} → key ${result.lastKey}")
        }
        val error = result.error ?: run {
            if (result.sent == 0) _state.update { it.copy(status = SyncStatus.IDLE) }   // queue was already empty
            return@withLock
        }
        if (monitor.isOnline.value) {
            onUploadFailed(error)
        } else {
            _state.update { it.copy(status = SyncStatus.PENDING) }
            log("Pending ($reason): немає мережі, записи чекають відправки")
        }
    }

    private fun onUploadFailed(e: Throwable) {
        val message = e.message ?: e.javaClass.simpleName
        _state.update { it.copy(status = SyncStatus.ERROR, lastError = message) }
        log("✗ Помилка: $message; записи чекають у черзі Pending")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${timeFormat.format(Date())}  $message"
        _state.update { it.copy(recent = (it.recent + line).takeLast(6)) }
    }

    override fun onCleared() {
        monitor.stop()
    }

    companion object {
        const val TAG = "CloudSync"
    }
}
