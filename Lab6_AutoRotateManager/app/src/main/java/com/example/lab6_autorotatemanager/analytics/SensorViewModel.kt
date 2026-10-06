package com.example.lab6_autorotatemanager.analytics

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab6_autorotatemanager.data.PeriodStats
import com.example.lab6_autorotatemanager.data.SensorRecord
import com.example.lab6_autorotatemanager.data.SensorRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Стан екрана «Аналітика», який не береться з бази напряму. */
data class AnalyticsUiState(
    val recording: Boolean = true,
    val writes: Int = 0,
    /** ЛР №6: прискорений запис (ML-модель розпізнала «Біг»). */
    val fastMode: Boolean = false,
    val lastWrite: String? = null,
    val error: String? = null
)

/**
 * ЛР №5: SensorViewModel — посередник між сенсором (MainActivity) і базою даних.
 *
 *   View (MainActivity / AnalyticsScreen)
 *     → SensorViewModel (RecordPolicy, StateFlow для UI)
 *       → SensorRepository (Dispatchers.IO)
 *         → SensorDao → Room → SQLite (auto_rotate.db)
 *
 * Запис іде «вниз» через onSample(), а дані для графіка піднімаються «вгору»
 * як Flow: Room сам повідомляє про нові рядки, тому графік оновлюється без
 * додаткових викликів — нова точка з'являється одразу після вставки.
 */
class SensorViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: SensorRepository = SensorRepository.from(app),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AndroidViewModel(app) {

    private val policy = RecordPolicy()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Стан тривоги на попередньому вимірі — щоб зловити саме момент спрацювання. */
    private var wasAlarm = false
    private var writesSinceCleanup = 0

    /** Записи виконуються по черзі (FIFO), щоб id у таблиці йшли в порядку вимірів. */
    private val writeMutex = Mutex()

    /** Останні CHART_POINTS записів для графіка (від старих до нових). */
    val chartPoints: StateFlow<List<SensorRecord>> = repository.latest(CHART_POINTS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Історія спрацювань системи безпеки. */
    val triggerHistory: StateFlow<List<SensorRecord>> = repository.triggered(HISTORY_SIZE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Кількість рядків у таблиці. */
    val recordCount: StateFlow<Int> = repository.count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Агрегати за останню годину; перераховуються після кожної зміни таблиці. */
    val hourStats: StateFlow<PeriodStats?> = repository.count()
        .map { repository.statsSince(clock() - HOUR_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    /**
     * Виклик з MainActivity.onSensorChanged (головний потік) після фільтра ЛР №1
     * і DecisionEngine ЛР №4. Сам запис виконується асинхронно.
     */
    fun onSample(sensorValue: Float, orientation: String, alarmActive: Boolean) {
        val triggered = alarmActive && !wasAlarm   // фронт 0 → 1: актуатори щойно ввімкнулися
        wasAlarm = alarmActive
        if (!_uiState.value.recording) return

        val now = clock()
        val reason = policy.check(sensorValue, now, force = triggered) ?: return
        val record = SensorRecord(
            timestamp = now,
            sensorValue = Math.round(sensorValue * 10f) / 10f,
            actionTriggered = triggered,
            orientation = orientation
        )

        viewModelScope.launch {
            try {
                val id = writeMutex.withLock { repository.insert(record) }   // усередині — withContext(Dispatchers.IO)
                Log.d(TAG, "INSERT #$id ${record.sensorValue}° action=${record.actionTriggered} (${reason.label})")
                _uiState.update {
                    it.copy(
                        writes = it.writes + 1,
                        lastWrite = "#$id · ${timeFormat.format(Date(now))} · ${record.sensorValue}° · ${reason.label}",
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
                _uiState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** ЛР №6: виклик з MainActivity, коли AiInsightsViewModel підтвердив/зняв стан «Біг». */
    fun setFastMode(enabled: Boolean) {
        if (policy.fastMode == enabled) return
        policy.fastMode = enabled
        _uiState.update { it.copy(fastMode = enabled) }
        Log.d(
            TAG,
            if (enabled) "ШІ: «Біг» → прискорений запис (кожні ${RecordPolicy.FAST_INTERVAL_MS} мс)"
            else "ШІ: звичайний режим запису (Δ ≥ ${RecordPolicy.DEFAULT_MIN_DELTA}° або раз на ${RecordPolicy.DEFAULT_HEARTBEAT_MS / 1000} с)"
        )
    }

    fun setRecording(enabled: Boolean) {
        _uiState.update { it.copy(recording = enabled) }
        if (enabled) policy.reset()   // перший вимір після паузи запишеться одразу
        Log.d(TAG, if (enabled) "Запис у БД увімкнено" else "Запис у БД призупинено")
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clear()
            policy.reset()
            _uiState.update { AnalyticsUiState(recording = it.recording) }
            Log.d(TAG, "Історію очищено")
        }
    }

    fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))

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
