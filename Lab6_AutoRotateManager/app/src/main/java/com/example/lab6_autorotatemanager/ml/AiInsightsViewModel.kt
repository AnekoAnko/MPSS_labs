package com.example.lab6_autorotatemanager.ml

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Стан блоку «AI Insights». */
data class AiUiState(
    val enabled: Boolean = true,
    val modelInfo: ModelInfo? = null,
    val modelError: String? = null,
    val bufferFill: Int = 0,
    val bufferSize: Int = SensorWindowBuffer.WINDOW_SIZE,
    /** Останній «сирий» прогноз моделі (оновлюється ~4 рази/с). */
    val prediction: Prediction? = null,
    /** Підтверджений клас (після PredictionSmoother) — саме він керує діями. */
    val stable: ActivityClass = ActivityClass.STILL,
    val inferenceCount: Int = 0,
    val avgInferenceMs: Float = 0f,
    val skippedWindows: Int = 0,
    val workerThread: String? = null,
    val history: List<String> = emptyList()
)

/**
 * ЛР №6: ViewModel локального ML (On-device AI).
 *
 *   MainActivity.onSensorChanged (головний потік, сирі x, y, z)
 *     → SensorWindowBuffer (кільцевий буфер, 20 вимірів ≈ 1 с)
 *       → кожні 5 вимірів: знімок вікна → Dispatchers.Default → ActivityClassifier (TFLite)
 *         → Prediction (ймовірності 4 класів) → PredictionSmoother → stable
 *           → StateFlow для UI (AiInsightsCard) і для інтеграції з ЛР №4/№5 (MainActivity)
 *
 * Inference — CPU-інтенсивна операція, тому вона виконується в пулі потоків Dispatchers.Default,
 * і головний потік (UI, сенсор) не блокується. Якщо попередній прогноз ще рахується,
 * нове вікно пропускається (backpressure), щоб черга задач не росла.
 */
class AiInsightsViewModel @JvmOverloads constructor(
    app: Application,
    private val classifierFactory: () -> IActivityClassifier = { ActivityClassifier(app) },
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(app) {

    private val buffer = SensorWindowBuffer()
    private val smoother = PredictionSmoother()
    private val busy = AtomicBoolean(false)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var classifier: IActivityClassifier? = null
    private var totalMs = 0.0

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    /** Підтверджена активність — на неї підписується MainActivity (ЛР №4, ЛР №5). */
    private val _stableActivity = MutableStateFlow(ActivityClass.STILL)
    val stableActivity: StateFlow<ActivityClass> = _stableActivity.asStateFlow()

    init {
        // Завантаження моделі (читання файлу, ініціалізація інтерпретатора) — теж не в UI-потоці
        viewModelScope.launch {
            try {
                val c = withContext(inferenceDispatcher) { classifierFactory() }
                classifier = c
                _uiState.update { it.copy(modelInfo = c.info, modelError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Не вдалося завантажити модель", e)
                _uiState.update { it.copy(modelError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /**
     * Виклик з MainActivity.onSensorChanged — СИРІ значення event.values (без фільтра ЛР №1:
     * низькочастотний фільтр прибрав би саме ту динаміку, за якою модель розпізнає рух).
     */
    fun onRawSample(x: Float, y: Float, z: Float, timestampNs: Long) {
        if (!_uiState.value.enabled) return
        val ready = buffer.add(x, y, z, timestampNs)
        if (_uiState.value.bufferFill != buffer.size) _uiState.update { it.copy(bufferFill = buffer.size) }
        if (!ready) return

        val model = classifier ?: return               // модель ще завантажується
        if (!busy.compareAndSet(false, true)) {        // попередній inference ще триває
            _uiState.update { it.copy(skippedWindows = it.skippedWindows + 1) }
            return
        }
        val window = buffer.toModelInput()             // знімок вікна на головному потоці

        viewModelScope.launch {
            try {
                val (prediction, thread) = withContext(inferenceDispatcher) {
                    model.classify(window) to Thread.currentThread().name
                }
                onPrediction(prediction, thread)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Помилка inference", e)
                _uiState.update { it.copy(modelError = e.message ?: e.javaClass.simpleName) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun onPrediction(prediction: Prediction, thread: String) {
        totalMs += prediction.inferenceMs
        val changed = smoother.update(prediction)
        val n = _uiState.value.inferenceCount + 1
        Log.d(
            TAG,
            "#$n ${prediction.format()} · ${"%.2f".format(prediction.inferenceMs)} мс · [$thread] · " +
                prediction.probabilities.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
        )
        _uiState.update {
            it.copy(
                prediction = prediction,
                stable = smoother.stable,
                inferenceCount = n,
                avgInferenceMs = (totalMs / n).toFloat(),
                workerThread = thread,
                history = if (changed == null) it.history else {
                    val line = "${timeFormat.format(Date(prediction.timestamp))}  ${it.stable.label} → ${changed.label} " +
                        "(${"%.0f".format(prediction.confidence * 100)}%)"
                    (it.history + line).takeLast(HISTORY_SIZE)
                }
            )
        }
        if (changed != null) {
            Log.i(TAG, "Стабільна активність: ${changed.name} (${prediction.format()})")
            _stableActivity.value = changed
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        if (!enabled) {
            buffer.clear()
            smoother.reset()
            _stableActivity.value = ActivityClass.STILL      // вимикаємо «наслідки» ML
            _uiState.update { it.copy(bufferFill = 0, prediction = null, stable = ActivityClass.STILL) }
        }
        Log.d(TAG, if (enabled) "Класифікацію увімкнено" else "Класифікацію вимкнено")
    }

    override fun onCleared() {
        classifier?.close()
    }

    companion object {
        const val TAG = "AI_Insights"
        const val HISTORY_SIZE = 6
    }
}
