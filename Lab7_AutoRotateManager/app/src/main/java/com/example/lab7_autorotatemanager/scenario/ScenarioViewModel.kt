package com.example.lab7_autorotatemanager.scenario

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab7_autorotatemanager.helpers.MedianNoiseFilter
import com.example.lab7_autorotatemanager.helpers.NoiseLevel
import com.example.lab7_autorotatemanager.helpers.SensorHub
import com.example.lab7_autorotatemanager.repository.ActuatorRequest
import com.example.lab7_autorotatemanager.repository.Metric
import com.example.lab7_autorotatemanager.repository.RequestSource
import com.example.lab7_autorotatemanager.repository.ScenarioRepository
import com.example.lab7_autorotatemanager.repository.ScenarioRule
import com.example.lab7_autorotatemanager.repository.SystemStateRepository
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
 * Smart scenarios, e.g. "IF X > 10 THEN vibrate".
 * raw samples → MedianNoiseFilter → ScenarioEngine → ActuatorRequest on the shared bus.
 * Rules are stored in Room; this ViewModel never touches hardware itself.
 */
class ScenarioViewModel(
    private val hub: SensorHub,
    private val repository: ScenarioRepository,
    private val system: SystemStateRepository,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val filter = MedianNoiseFilter(NoiseLevel.MEDIUM.window)
    private val engine = ScenarioEngine()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var rules: List<ScenarioRule> = emptyList()
    private var sampleCount = 0

    private val _state = MutableStateFlow(ScenarioState())
    val state: StateFlow<ScenarioState> = _state.asStateFlow()

    private val _events = Channel<ScenarioEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                repository.ensureDefaults()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Не вдалося створити правила за замовчуванням", e)
            }
        }
        // Rules from Room → state
        viewModelScope.launch {
            repository.rules.collect { list ->
                rules = list
                publishRules()
            }
        }
        // Raw values; noise filtering is done here so its level is configurable
        viewModelScope.launch {
            hub.raw.collect { s -> onSample(s.x, s.y, s.z) }
        }
    }

    // Single entry point for UI actions
    fun onAction(action: ScenarioAction) {
        when (action) {
            is ScenarioAction.SetEnabled -> setEnabled(action.enabled)
            is ScenarioAction.SetNoise -> setNoise(action.level)
            is ScenarioAction.ToggleRule -> viewModelScope.launch { repository.setEnabled(action.id, action.enabled) }
            is ScenarioAction.DeleteRule -> viewModelScope.launch { repository.delete(action.id) }
            is ScenarioAction.DraftMetric -> _state.update { it.copy(draft = it.draft.copy(metric = action.metric), draftError = null) }
            is ScenarioAction.DraftComparison -> _state.update { it.copy(draft = it.draft.copy(comparison = action.comparison)) }
            is ScenarioAction.DraftThreshold -> _state.update { it.copy(draft = it.draft.copy(threshold = action.text), draftError = null) }
            is ScenarioAction.DraftAction -> _state.update { it.copy(draft = it.draft.copy(action = action.action)) }
            is ScenarioAction.DraftHold -> _state.update { it.copy(draft = it.draft.copy(holdMs = action.ms.coerceIn(0, MAX_HOLD_MS))) }
            ScenarioAction.AddRule -> addRule()
        }
    }

    private fun onSample(rawX: Float, rawY: Float, rawZ: Float) {
        val (x, y, z) = filter.apply(rawX, rawY, rawZ)
        // Update the UI ~5 times per second
        if (++sampleCount % UI_EVERY == 0) {
            _state.update {
                it.copy(x = x, y = y, z = z, dynamic = Metric.DYNAMIC.of(x, y, z), tilt = Metric.TILT.of(x, y, z))
            }
        }
        if (!_state.value.enabled) return
        val transitions = engine.evaluate(rules, x, y, z, clock())
        if (transitions.isNotEmpty()) applyTransitions(transitions)
    }

    private fun applyTransitions(transitions: List<ScenarioTransition>) {
        for (t in transitions) {
            val rule = t.rule
            val reason = rule.describe()
            system.request(ActuatorRequest(rule.action, on = t.active, source = RequestSource.SCENARIO, reason = reason))
            if (t.active) {
                log("СПРАЦЮВАЛО: $reason (значення ${"%.1f".format(t.value)})")
                _state.update { it.copy(firedCount = it.firedCount + 1) }
            } else {
                log("Знято: ${rule.describe()}")
            }
        }
        publishRules()
    }

    private fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        if (!enabled) applyTransitions(engine.releaseAll())
        log(if (enabled) "Smart-сценарії увімкнено" else "Smart-сценарії вимкнено")
    }

    private fun setNoise(level: NoiseLevel) {
        filter.window = level.window
        _state.update { it.copy(noise = level) }
        log("Шумопоглинання: ${level.label}")
    }

    private fun addRule() {
        val d = _state.value.draft
        val threshold = d.threshold.replace(',', '.').toFloatOrNull()
        if (threshold == null || threshold.isNaN() || threshold.isInfinite()) {
            _state.update { it.copy(draftError = "Поріг має бути числом, напр. 10 або 7,5") }
            return
        }
        val rule = ScenarioRule(metric = d.metric, comparison = d.comparison, threshold = threshold, action = d.action, holdMs = d.holdMs)
        viewModelScope.launch {
            repository.save(rule)
            _events.send(ScenarioEvent.ShowMessage("Додано: ${rule.describe()}"))
            log("Нове правило: ${rule.describe()}")
        }
    }

    private fun publishRules() {
        val active = engine.activeIds
        _state.update { s -> s.copy(rules = rules.map { RuleItem(it, it.id in active) }) }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${timeFormat.format(Date(clock()))}  $message"
        _state.update { it.copy(log = (it.log + line).takeLast(LOG_SIZE)) }
    }

    companion object {
        const val TAG = "SmartScenario"
        const val LOG_SIZE = 8
        const val UI_EVERY = 4
        const val MAX_HOLD_MS = 2_000L
    }
}
