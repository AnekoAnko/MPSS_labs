package com.example.lab5_autorotatemanager.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Спільний стан системи, який потрібен кільком екранам.
 * ViewModel не звертаються одна до одної: одна записує значення сюди,
 * інші отримують його як Flow.
 *
 *   MonitorViewModel  → orientation  → CloudViewModel (поле orientation), BleViewModel (ORIENT:…)
 *   ControlViewModel  → threshold, alarmActive → AnalyticsViewModel (ЛР №5: поріг на графіку, позначка спрацювання)
 */
class SystemStateRepository {

    private val _orientation = MutableStateFlow("PORTRAIT")
    /** Стан Auto-Rotate Manager з ЛР №1 (PORTRAIT, LANDSCAPE_LEFT, …). */
    val orientation: StateFlow<String> = _orientation.asStateFlow()

    private val _threshold = MutableStateFlow(DEFAULT_THRESHOLD)
    /** Поточний поріг DecisionEngine (ЛР №4), градуси. */
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _alarmActive = MutableStateFlow(false)
    /** Чи увімкнені зараз актуатори системи безпеки (ЛР №4). */
    val alarmActive: StateFlow<Boolean> = _alarmActive.asStateFlow()

    fun setOrientation(value: String) { _orientation.value = value }
    fun setThreshold(value: Float) { _threshold.value = value }
    fun setAlarmActive(value: Boolean) { _alarmActive.value = value }

    companion object {
        const val DEFAULT_THRESHOLD = 60f
    }
}
