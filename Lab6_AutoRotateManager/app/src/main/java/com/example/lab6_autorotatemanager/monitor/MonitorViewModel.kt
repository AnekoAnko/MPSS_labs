package com.example.lab6_autorotatemanager.monitor

import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab6_autorotatemanager.AppGraph
import com.example.lab6_autorotatemanager.helpers.SensorHub
import com.example.lab6_autorotatemanager.helpers.SensorReader
import com.example.lab6_autorotatemanager.helpers.SensorSample
import com.example.lab6_autorotatemanager.repository.SystemStateRepository
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
 * Sensor tab: reads the accelerometer, publishes samples to SensorHub
 * and detects device orientation.
 */
class MonitorViewModel(
    private val reader: SensorReader = SensorReader(AppGraph.app),
    private val hub: SensorHub = AppGraph.sensorHub,
    private val system: SystemStateRepository = AppGraph.systemState,
    // 20 Hz: the rate the ML model was trained on
    private val samplingPeriodUs: Int = SensorReader.PERIOD_20_HZ_US,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : ViewModel() {

    private val detector = OrientationDetector()

    // private mutable state, public read-only
    private val _state = MutableStateFlow(MonitorState(sensorAvailable = reader.isAvailable))
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    // Channel delivers each event exactly once
    private val _events = Channel<MonitorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var sensorJob: Job? = null

    // Single entry point for UI actions
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
                onSample(hub.publish(raw))   // filter + broadcast to other screens
            }
        }
    }

    private fun stopSensor() {
        sensorJob?.cancel()   // cancelling the Flow unregisters the listener
        sensorJob = null
        _state.update { it.copy(isRunning = false) }
    }

    private fun onSample(s: SensorSample) {
        val angle = Math.toDegrees(atan2(s.x.toDouble(), s.y.toDouble())).toFloat()
        _state.update { it.copy(x = s.x, y = s.y, z = s.z, screenAngle = angle) }

        val changed = detector.update(s.x, s.y, s.z, clock()) ?: return
        _state.update { it.copy(orientation = changed) }
        system.setOrientation(changed.name)   // → cloud, BLE hub
        if (_state.value.autoRotate) changed.screenOrientation?.let { _events.trySend(MonitorEvent.RotateScreen(it)) }
    }

    private fun setAutoRotate(enabled: Boolean) {
        _state.update { it.copy(autoRotate = enabled) }
        val target = if (enabled) _state.value.orientation.screenOrientation
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        target?.let { _events.trySend(MonitorEvent.RotateScreen(it)) }
    }
}
