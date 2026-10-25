package com.example.lab7_autorotatemanager.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab7_autorotatemanager.helpers.IActivityClassifier
import com.example.lab7_autorotatemanager.helpers.SensorHub
import com.example.lab7_autorotatemanager.repository.AiSnapshot
import com.example.lab7_autorotatemanager.repository.SystemStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ЛР №6: ViewModel локального ML (On-device AI).
 *
 *   SensorHub.raw (СИРІ x, y, z, 20 Гц)
 *     → SensorWindowBuffer (кільцевий буфер, 20 вимірів ≈ 1 с)
 *       → кожні 5 вимірів: знімок вікна → Dispatchers.Default → помічник ActivityClassifier (TFLite)
 *         → Prediction (ймовірності 4 класів) → PredictionSmoother → stable
 *           → State для UI і SystemStateRepository.aiActivity для ЛР №4/№5
 *
 * Inference — CPU-інтенсивна операція, тому вона виконується в Dispatchers.Default.
 * Якщо попередній прогноз ще рахується, нове вікно пропускається (backpressure).
 */
class AiViewModel(
    private val classifierFactory: () -> IActivityClassifier,
    private val hub: SensorHub,
    private val system: SystemStateRepository,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val buffer = SensorWindowBuffer()
    private val smoother = PredictionSmoother()
    private val busy = AtomicBoolean(false)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var classifier: IActivityClassifier? = null
    private var totalMs = 0.0

    private val _state = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    private val _events = Channel<AiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Завантаження моделі (читання файлу, ініціалізація інтерпретатора) — не в UI-потоці
        viewModelScope.launch {
            try {
                val c = withContext(inferenceDispatcher) { classifierFactory() }
                classifier = c
                _state.update { it.copy(modelInfo = c.info, modelError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Не вдалося завантажити модель", e)
                _state.update { it.copy(modelError = e.message ?: e.javaClass.simpleName) }
                _events.send(AiEvent.ShowMessage("Модель не завантажено: ${e.message}"))
            }
        }

        // СИРІ значення (без фільтра ЛР №1: він прибрав би саме ту динаміку, за якою модель розпізнає рух)
        viewModelScope.launch {
            hub.raw.collect { s -> onRawSample(s.x, s.y, s.z, s.timestampNs) }
        }
    }

    // ЄДИНИЙ вхід для дій користувача
    fun onAction(action: AiAction) {
        when (action) {
            is AiAction.SetEnabled -> setEnabled(action.enabled)
        }
    }

    private fun onRawSample(x: Float, y: Float, z: Float, timestampNs: Long) {
        if (!_state.value.enabled) return
        val ready = buffer.add(x, y, z, timestampNs)
        if (_state.value.bufferFill != buffer.size) _state.update { it.copy(bufferFill = buffer.size) }
        if (!ready) return

        val model = classifier ?: return               // модель ще завантажується
        if (!busy.compareAndSet(false, true)) {        // попередній inference ще триває
            _state.update { it.copy(skippedWindows = it.skippedWindows + 1) }
            return
        }
        val window = buffer.toModelInput()             // знімок вікна

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
                _state.update { it.copy(modelError = e.message ?: e.javaClass.simpleName) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun onPrediction(prediction: Prediction, thread: String) {
        totalMs += prediction.inferenceMs
        val changed = smoother.update(prediction)
        val n = _state.value.inferenceCount + 1
        Log.d(
            TAG,
            "#$n ${prediction.format()} · ${"%.2f".format(prediction.inferenceMs)} мс · [$thread] · " +
                prediction.probabilities.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
        )
        _state.update {
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
        // ЛР №7: віджет поточного стану на Dashboard
        system.setAi(AiSnapshot(enabled = true, stable = smoother.stable, current = prediction.best, confidence = prediction.confidence))
        if (changed != null) {
            Log.i(TAG, "Стабільна активність: ${changed.name} (${prediction.format()})")
            system.setAiActivity(changed)   // → ЛР №4 (Аномалія → актуатори), ЛР №5 (Біг → прискорений запис)
        }
    }

    private fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        if (!enabled) {
            buffer.clear()
            smoother.reset()
            system.setAiActivity(ActivityClass.STILL)      // вимикаємо «наслідки» ML
            _state.update { it.copy(bufferFill = 0, prediction = null, stable = ActivityClass.STILL) }
        }
        system.setAi(AiSnapshot(enabled = enabled))
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
