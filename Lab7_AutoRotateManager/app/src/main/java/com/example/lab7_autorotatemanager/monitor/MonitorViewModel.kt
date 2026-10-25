package com.example.lab7_autorotatemanager.monitor

import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab7_autorotatemanager.helpers.SensorHub
import com.example.lab7_autorotatemanager.helpers.SensorReader
import com.example.lab7_autorotatemanager.helpers.SensorSample
import com.example.lab7_autorotatemanager.repository.SystemStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.atan2

/**
 * ViewModel вкладки «Сенсор» (ЛР №1). Слухає акселерометр через помічник SensorReader,
 * передає кожен вимір у спільний SensorHub (для хмари, керування й інших екранів)
 * і визначає орієнтацію пристрою.
 */
class MonitorViewModel(
    private val reader: SensorReader,
    private val hub: SensorHub,
    private val system: SystemStateRepository,
    // ЛР №6: 20 Гц (період 50 000 мкс) — частота, на якій навчена модель (вікно 20 вимірів ≈ 1 с)
    private val samplingPeriodUs: Int = SensorReader.PERIOD_20_HZ_US,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : ViewModel() {

    private val detector = OrientationDetector()

    // State: приватний змінюваний + публічний тільки для читання
    private val _state = MutableStateFlow(MonitorState(sensorAvailable = reader.isAvailable))
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    // Event: Channel доставляє кожну подію рівно один раз
    private val _events = Channel<MonitorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var sensorJob: Job? = null

    // ЄДИНИЙ вхід у ViewModel — всі дії приходять сюди
    fun onAction(action: MonitorAction) {
        when (action) {
            MonitorAction.Start -> startSensor()
            MonitorAction.Stop -> stopSensor()
            is MonitorAction.SetAutoRotate -> setAutoRotate(action.enabled)
        }
    }

    private fun startSensor() {
        if (sensorJob != null) return
        _state.update { it.copy(isRunning = true) }
        sensorJob = viewModelScope.launch {
            reader.readings(samplingPeriodUs).collect { raw ->
                onSample(hub.publish(raw))   // фільтр ЛР №1 + розсилка іншим екранам
            }
        }
    }

    private fun stopSensor() {
        sensorJob?.cancel()   // скасування Flow → unregisterListener()
        sensorJob = null
        _state.update { it.copy(isRunning = false) }
    }

    private fun onSample(s: SensorSample) {
        val angle = Math.toDegrees(atan2(s.x.toDouble(), s.y.toDouble())).toFloat()
        _state.update { it.copy(x = s.x, y = s.y, z = s.z, screenAngle = angle) }

        val changed = detector.update(s.x, s.y, s.z, clock()) ?: return
        _state.update { it.copy(orientation = changed) }
        system.setOrientation(changed.name)   // → хмара (ЛР №3), BLE-хаб (ЛР №2)
        if (_state.value.autoRotate) changed.screenOrientation?.let { _events.trySend(MonitorEvent.RotateScreen(it)) }
    }

    private fun setAutoRotate(enabled: Boolean) {
        _state.update { it.copy(autoRotate = enabled) }
        val target = if (enabled) _state.value.orientation.screenOrientation
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        target?.let { _events.trySend(MonitorEvent.RotateScreen(it)) }
    }
}
