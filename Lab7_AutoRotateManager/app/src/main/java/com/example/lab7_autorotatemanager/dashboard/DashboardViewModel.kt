package com.example.lab7_autorotatemanager.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab7_autorotatemanager.repository.ActuatorRequest
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.RequestSource
import com.example.lab7_autorotatemanager.repository.SensorRecord
import com.example.lab7_autorotatemanager.repository.SensorRepository
import com.example.lab7_autorotatemanager.repository.SystemStateRepository
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
 * ЛР №7: головний екран. Сам нічого не вимірює і не відправляє — лише збирає в один State
 * те, що публікують інші модулі (SystemStateRepository) і що лежить у Room (SensorRepository).
 * Кнопки швидкого керування не чіпають залізо: надсилають ActuatorRequest у спільну шину,
 * а виконує його ControlViewModel (ЛР №4) або BleViewModel (реле хаба, ЛР №2).
 */
class DashboardViewModel(
    private val system: SystemStateRepository,
    private val repository: SensorRepository
) : ViewModel() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val _events = Channel<DashboardEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch { system.ai.collect { a -> _state.update { it.copy(ai = a) } } }
        viewModelScope.launch { system.alarmActive.collect { c -> _state.update { it.copy(critical = c) } } }
        viewModelScope.launch { system.orientation.collect { o -> _state.update { it.copy(orientation = o) } } }
        viewModelScope.launch { system.cloud.collect { c -> _state.update { it.copy(cloud = c) } } }
        viewModelScope.launch { system.ble.collect { b -> _state.update { it.copy(ble = b) } } }
        viewModelScope.launch { system.actuators.collect { a -> _state.update { it.copy(actuators = a) } } }
        // Room сам оновлює Flow після кожного INSERT/UPDATE (synced) — список «живий»
        viewModelScope.launch {
            repository.recent(RECENT).collect { list -> _state.update { it.copy(records = list.map(::toItem)) } }
        }
        viewModelScope.launch { repository.storageFull.collect { f -> _state.update { it.copy(storageFull = f) } } }
        viewModelScope.launch { repository.count().collect { n -> _state.update { it.copy(recordCount = n) } } }
    }

    // ЄДИНИЙ вхід для дій користувача
    fun onAction(action: DashboardAction) {
        when (action) {
            is DashboardAction.Toggle -> toggle(action.type, action.on)
            DashboardAction.AllOff -> listOf(ActuatorType.SIREN, ActuatorType.FLASHLIGHT, ActuatorType.LAMP, ActuatorType.HUB_RELAY)
                .forEach { send(it, false) }
        }
    }

    private fun toggle(type: ActuatorType, on: Boolean) {
        if (type == ActuatorType.HUB_RELAY && !_state.value.ble.linkUp) {
            _events.trySend(DashboardEvent.ShowMessage("BLE-хаб не підключено: вкладка «BLE-хаб» → Пошук → Підключити"))
            return
        }
        send(type, on)
    }

    private fun send(type: ActuatorType, on: Boolean) {
        if (!system.request(ActuatorRequest(type, on, RequestSource.DASHBOARD, "кнопка Dashboard"))) {
            _events.trySend(DashboardEvent.ShowMessage("Команду не прийнято: черга переповнена"))
        }
    }

    private fun toItem(r: SensorRecord) = RecordItem(
        time = timeFormat.format(Date(r.timestamp)),
        tilt = r.sensorValue,
        orientation = r.orientation,
        activity = r.activity,
        triggered = r.actionTriggered,
        synced = r.synced
    )

    companion object {
        const val RECENT = 5
    }
}
