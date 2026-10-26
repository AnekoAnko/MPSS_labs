package com.example.lab5_autorotatemanager.analytics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab5_autorotatemanager.AppGraph
import com.example.lab5_autorotatemanager.control.DecisionEngine
import com.example.lab5_autorotatemanager.helpers.SensorHub
import com.example.lab5_autorotatemanager.repository.SensorReading
import com.example.lab5_autorotatemanager.repository.SensorRepository
import com.example.lab5_autorotatemanager.repository.SystemStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Analytics tab: decides which samples to store (RecordPolicy) and saves them via SensorRepository.
 * Chart data comes back from Room as a Flow, so the chart updates after every insert.
 */
class AnalyticsViewModel(
    private val repository: SensorRepository = AppGraph.sensorRepository,
    private val hub: SensorHub = AppGraph.sensorHub,
    private val system: SystemStateRepository = AppGraph.systemState,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val policy = RecordPolicy()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Alarm state on the previous sample, used to detect the moment it turns on. */
    private var wasAlarm = false
    private var writesSinceCleanup = 0

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    private val _events = Channel<AnalyticsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Room re-emits after every insert
        viewModelScope.launch { repository.latest(CHART_POINTS).collect { p -> _state.update { it.copy(points = p) } } }
        viewModelScope.launch {
            repository.triggered(HISTORY_SIZE).collect { list ->
                _state.update { s -> s.copy(history = list.map { TriggerItem(format(it.timestamp), it.sensorValue, it.orientation) }) }
            }
        }
        viewModelScope.launch {
            repository.count().collect { n ->
                val stats = repository.statsSince(clock() - HOUR_MS)   // recompute aggregates after each table change
                _state.update { it.copy(recordCount = n, hourStats = stats) }
            }
        }
        viewModelScope.launch { system.threshold.collect { t -> _state.update { it.copy(threshold = t) } } }

        // Tilt from filtered accelerometer data
        viewModelScope.launch {
            hub.filtered.collect { s ->
                onSample(s.x, s.y, s.z, system.orientation.value, system.alarmActive.value)
            }
        }
    }

    // Single entry point for UI actions
    fun onAction(action: AnalyticsAction) {
        when (action) {
            is AnalyticsAction.SetRecording -> setRecording(action.enabled)
            AnalyticsAction.ClearHistory -> clearHistory()
        }
    }

    private fun onSample(x: Float, y: Float, z: Float, orientation: String, alarmActive: Boolean) {
        val triggered = alarmActive && !wasAlarm   // rising edge: actuators just turned on
        wasAlarm = alarmActive
        if (!_state.value.recording) return

        val tilt = DecisionEngine.tiltDegrees(x, y, z)
        val now = clock()
        val reason = policy.check(tilt, now, force = triggered) ?: return
        val reading = SensorReading(x, y, z, tilt, orientation, actionTriggered = triggered, timestamp = now)

        viewModelScope.launch {
            try {
                // Room first, then cloud
                val cloud = repository.save(reading)
                val where = if (cloud.isSuccess) "☁ у хмарі" else "лише локально · Pending"
                val value = "%.1f".format(tilt)
                Log.d(TAG, "INSERT $value° action=$triggered (${reason.label}) → $where")
                _state.update {
                    it.copy(
                        writes = it.writes + 1,
                        lastWrite = "${format(now)} · $value° · ${reason.label} · $where",
                        error = null
                    )
                }
                // Periodically delete records older than RETENTION_MS
                if (++writesSinceCleanup >= CLEANUP_EVERY) {
                    writesSinceCleanup = 0
                    val removed = repository.deleteOlderThan(clock() - RETENTION_MS)
                    if (removed > 0) Log.d(TAG, "Ротація: видалено $removed старих записів")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Помилка запису в БД", e)
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun setRecording(enabled: Boolean) {
        _state.update { it.copy(recording = enabled) }
        if (enabled) policy.reset()   // next sample is stored immediately
        Log.d(TAG, if (enabled) "Запис у БД увімкнено" else "Запис у БД призупинено")
    }

    private fun clearHistory() {
        viewModelScope.launch {
            repository.clear()
            policy.reset()
            _state.update { it.copy(writes = 0, lastWrite = null, error = null) }
            _events.send(AnalyticsEvent.ShowMessage("Історію очищено"))
            Log.d(TAG, "Історію очищено")
        }
    }

    private fun format(timestamp: Long): String = timeFormat.format(Date(timestamp))

    companion object {
        const val TAG = "SensorDB"
        /** Limit chart points to keep rendering cheap. */
        const val CHART_POINTS = 50
        const val HISTORY_SIZE = 10
        const val HOUR_MS = 60 * 60 * 1000L
        const val RETENTION_MS = 24 * HOUR_MS
        const val CLEANUP_EVERY = 100
    }
}
