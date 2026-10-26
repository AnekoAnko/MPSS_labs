package com.example.lab5_autorotatemanager.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state between screens. ViewModels never call each other:
 * one writes a value here, others observe it as a Flow.
 */
class SystemStateRepository {

    private val _orientation = MutableStateFlow("PORTRAIT")
    /** Device orientation (PORTRAIT, LANDSCAPE_LEFT, …). */
    val orientation: StateFlow<String> = _orientation.asStateFlow()

    private val _threshold = MutableStateFlow(DEFAULT_THRESHOLD)
    /** Current DecisionEngine threshold, degrees. */
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _alarmActive = MutableStateFlow(false)
    /** Whether the alarm actuators are on. */
    val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()

    fun setOrientation(value: String) { _orientation.value = value }
    fun setThreshold(value: Float) { _threshold.value = value }
    fun setAlarmActive(value: Boolean) { _alarmActive.value = value }

    companion object {
        const val DEFAULT_THRESHOLD = 60f
    }
}
