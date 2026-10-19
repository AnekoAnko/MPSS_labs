package com.example.lab6_autorotatemanager.analytics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab6_autorotatemanager.AppGraph
import com.example.lab6_autorotatemanager.ai.ActivityClass
import com.example.lab6_autorotatemanager.control.DecisionEngine
import com.example.lab6_autorotatemanager.helpers.SensorHub
import com.example.lab6_autorotatemanager.repository.SensorReading
import com.example.lab6_autorotatemanager.repository.SensorRepository
import com.example.lab6_autorotatemanager.repository.SystemStateRepository
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
 * ЛР №5: ViewModel вкладки «Аналітика».
 *
 *   SensorHub (ЛР №1) + SystemStateRepository (тривога ЛР №4)
 *     → AnalyticsViewModel (RecordPolicy)
 *       → SensorRepository (Dispatchers.IO) → SensorDao → Room → SQLite (auto_rotate.db)
 *
 * Запис іде «вниз» через repository.save() (спершу Room, потім хмара), а дані для графіка піднімаються «вгору»
 * як Flow: Room сам повідомляє про нові рядки, і ViewModel кладе їх у State.
 */
class AnalyticsViewModel(
    private val repository: SensorRepository = AppGraph.sensorRepository,
    private val hub: SensorHub = AppGraph.sensorHub,
    private val system: SystemStateRepository = AppGraph.systemState,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val policy = RecordPolicy()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Стан тривоги на попередньому вимірі — щоб зловити саме момент спрацювання. */
    private var wasAlarm = false
    private var writesSinceCleanup = 0

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    private val _events = Channel<AnalyticsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Дані з бази → State (Room оновлює Flow після кожної вставки)
        viewModelScope.launch { repository.latest(CHART_POINTS).collect { p -> _state.update { it.copy(points = p) } } }
        viewModelScope.launch {
            repository.triggered(HISTORY_SIZE).collect { list ->
                _state.update { s -> s.copy(history = list.map { TriggerItem(format(it.timestamp), it.sensorValue, it.orientation) }) }
            }
        }
        viewModelScope.launch {
            repository.count().collect { n ->
                val stats = repository.statsSince(clock() - HOUR_MS)   // агрегати перераховуємо після кожної зміни таблиці
                _state.update { it.copy(recordCount = n, hourStats = stats) }
            }
        }
        viewModelScope.launch { system.threshold.collect { t -> _state.update { it.copy(threshold = t) } } }
        // ЛР №6: «Біг» за прогнозом моделі → прискорений запис
        viewModelScope.launch { system.aiActivity.collect { a -> setFastMode(a == ActivityClass.RUNNING) } }

        // Виміри: кут нахилу з даних акселерометра після фільтра ЛР №1
        viewModelScope.launch {
            hub.filtered.collect { s ->
                onSample(s.x, s.y, s.z, system.orientation.value, system.alarmActive.value)
            }
        }
    }

    // ЄДИНИЙ вхід для дій користувача
    fun onAction(action: AnalyticsAction) {
        when (action) {
            is AnalyticsAction.SetRecording -> setRecording(action.enabled)
            AnalyticsAction.ClearHistory -> clearHistory()
        }
    }

    private fun onSample(x: Float, y: Float, z: Float, orientation: String, alarmActive: Boolean) {
        val triggered = alarmActive && !wasAlarm   // фронт 0 → 1: актуатори щойно ввімкнулися
        wasAlarm = alarmActive
        if (!_state.value.recording) return

        val tilt = DecisionEngine.tiltDegrees(x, y, z)
        val now = clock()
        val reason = policy.check(tilt, now, force = triggered) ?: return
        val reading = SensorReading(x, y, z, tilt, orientation, actionTriggered = triggered, timestamp = now)

        viewModelScope.launch {
            try {
                // Репозиторій сам вирішує, куди писати: спершу Room, потім хмара (synced = true)
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
                // Ротація: раз на CLEANUP_EVERY записів видаляємо дані, старші за RETENTION_MS
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

    private fun setFastMode(enabled: Boolean) {
        if (policy.fastMode == enabled) return
        policy.fastMode = enabled
        _state.update { it.copy(fastMode = enabled) }
        Log.d(
            TAG,
            if (enabled) "ШІ: «Біг» → прискорений запис (кожні ${RecordPolicy.FAST_INTERVAL_MS} мс)"
            else "ШІ: звичайний режим запису (Δ ≥ ${RecordPolicy.DEFAULT_MIN_DELTA}° або раз на ${RecordPolicy.DEFAULT_HEARTBEAT_MS / 1000} с)"
        )
    }

    private fun setRecording(enabled: Boolean) {
        _state.update { it.copy(recording = enabled) }
        if (enabled) policy.reset()   // перший вимір після паузи запишеться одразу
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
        /** Обмеження кількості точок на графіку, щоб не перевантажувати рендеринг. */
        const val CHART_POINTS = 50
        const val HISTORY_SIZE = 10
        const val HOUR_MS = 60 * 60 * 1000L
        const val RETENTION_MS = 24 * HOUR_MS
        const val CLEANUP_EVERY = 100
    }
}
