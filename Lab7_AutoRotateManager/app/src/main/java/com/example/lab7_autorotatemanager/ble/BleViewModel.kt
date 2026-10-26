package com.example.lab7_autorotatemanager.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab7_autorotatemanager.helpers.ble.BleConnectionState
import com.example.lab7_autorotatemanager.helpers.ble.BleDevice
import com.example.lab7_autorotatemanager.helpers.ble.BleLogger
import com.example.lab7_autorotatemanager.helpers.ble.BleProfile
import com.example.lab7_autorotatemanager.helpers.ble.BluetoothEnvironment
import com.example.lab7_autorotatemanager.helpers.ble.IBleConnector
import com.example.lab7_autorotatemanager.helpers.ble.IBleScanner
import com.example.lab7_autorotatemanager.helpers.ble.LogType
import com.example.lab7_autorotatemanager.helpers.ble.MockBleConnector
import com.example.lab7_autorotatemanager.helpers.ble.MockBleScanner
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.BleLink
import com.example.lab7_autorotatemanager.repository.SystemStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * BLE hub tab. Talks to Bluetooth only through IBleScanner / IBleConnector (mock AR-Hub-46).
 * Also sends ALARM:ON/OFF on critical state, drives the hub relay and publishes link status for the dashboard.
 */
class BleViewModel(
    private val environment: BluetoothEnvironment,
    private val system: SystemStateRepository
) : ViewModel() {

    private val logger = BleLogger()
    private val scanner: IBleScanner = MockBleScanner(logger)
    private val mockConnector = MockBleConnector(logger, viewModelScope)
    private val connector: IBleConnector = mockConnector

    private val _state = MutableStateFlow(BleState())
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val _events = Channel<BleEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    /** Last command sent by the system (shown on the dashboard). */
    private var lastCommand: String? = null

    init {
        // Log → state
        viewModelScope.launch { logger.entries.collect { log -> _state.update { it.copy(log = log) } } }

        // Connection state → state
        viewModelScope.launch {
            connector.connectionState.collect { cs ->
                _state.update {
                    it.copy(
                        connectionState = cs,
                        connectedDevice = if (cs == BleConnectionState.DISCONNECTED) null else it.connectedDevice,
                        lastStatus = if (cs == BleConnectionState.DISCONNECTED) null else it.lastStatus
                    )
                }
            }
        }
        // Notify from the device → last status
        viewModelScope.launch {
            connector.notifications.collect { n -> _state.update { it.copy(lastStatus = n.value) } }
        }
        // Link status → dashboard
        viewModelScope.launch {
            _state.collect { s ->
                system.setBle(
                    BleLink(
                        state = s.connectionState.label,
                        linkUp = s.connectionState.isLinkUp,
                        device = s.connectedDevice?.name,
                        lastStatus = s.lastStatus,
                        lastCommand = lastCommand
                    )
                )
            }
        }
        // Hub relay reply → dashboard
        viewModelScope.launch {
            connector.notifications.collect { n ->
                when {
                    n.value.contains("RELAY=ON") || n.value.startsWith("relay=ON") -> system.updateActuators { it.copy(hubRelay = true) }
                    n.value.contains("RELAY=OFF") || n.value.startsWith("relay=OFF") -> system.updateActuators { it.copy(hubRelay = false) }
                }
            }
        }
        // Critical state → hub alarm
        viewModelScope.launch {
            system.alarmActive.drop(1).collect { critical -> sendToHub(if (critical) "ALARM:ON" else "ALARM:OFF") }
        }
        // Hub relay requests from dashboard and scenarios
        viewModelScope.launch {
            system.requests.collect { r ->
                if (r.type == ActuatorType.HUB_RELAY) sendToHub(if (r.on) "RELAY:ON" else "RELAY:OFF")
            }
        }

        // Orientation change → ORIENTATION characteristic
        viewModelScope.launch {
            system.orientation.drop(1).collect { orientation ->
                if (_state.value.connectionState.isLinkUp) {
                    connector.sendData("ORIENT:$orientation", BleProfile.CHAR_ORIENTATION)
                }
            }
        }
    }

    // Single entry point for UI actions
    fun onAction(action: BleAction) {
        when (action) {
            BleAction.ScanClick -> _events.trySend(BleEvent.RequestPermissions(BluetoothEnvironment.REQUIRED_PERMISSIONS))
            is BleAction.PermissionsResult -> onPermissions(action.granted)
            BleAction.StopScan -> scanJob?.cancel()
            is BleAction.Connect -> connect(action.device)
            BleAction.Disconnect -> disconnect()
            is BleAction.CommandChanged -> _state.update { it.copy(commandText = action.text) }
            is BleAction.SendCommand -> sendCommand(action.command)
            is BleAction.SetInterference -> setInterference(action.enabled)
            BleAction.ClearLog -> logger.clear()
        }
    }

    private fun onPermissions(granted: Boolean) {
        val adapter = environment.isAdapterAvailable()
        logger.log(
            LogType.INFO,
            "BluetoothAdapter: ${if (adapter) "є" else "відсутній"}; " +
                "дозволи: ${if (granted) "надано" else "не надано"}; режим: Mock (IBleConnector → MockBleConnector)"
        )
        // The mock layer has no radio, so scanning works without permissions
        startScan()
    }

    private fun startScan() {
        if (scanJob?.isActive == true || _state.value.connectionState != BleConnectionState.DISCONNECTED) return
        _state.update { it.copy(isScanning = true, devices = emptyList()) }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan(SCAN_DURATION_MS).collect { device ->
                    _state.update { s ->
                        if (s.devices.any { it.address == device.address }) s
                        else s.copy(devices = s.devices + device)
                    }
                }
            } finally {
                _state.update { it.copy(isScanning = false) }
            }
        }
    }

    private fun connect(device: BleDevice) {
        if (_state.value.connectionState != BleConnectionState.DISCONNECTED) return
        scanJob?.cancel() // scanning while connecting hurts stability
        _state.update { it.copy(connectedDevice = device) }
        connectJob = viewModelScope.launch {
            if (connector.connect(device)) {
                connector.readCharacteristic(BleProfile.CHAR_DEVICE_STATUS)?.let { status ->
                    _state.update { it.copy(lastStatus = status) }
                }
                connector.sendData("ORIENT:${system.orientation.value}", BleProfile.CHAR_ORIENTATION)
                // alarm started while disconnected: deliver it now
                if (system.alarmActive.value) sendToHub("ALARM:ON")
            } else {
                _state.update { it.copy(connectedDevice = null) }
            }
        }
    }

    private fun disconnect() {
        connectJob?.cancel()
        connector.disconnect()
    }

    private fun sendCommand(preset: String?) {
        val command = (preset ?: _state.value.commandText).trim()
        if (command.isEmpty()) return
        if (preset == null) _state.update { it.copy(commandText = "") }
        viewModelScope.launch { connector.sendData(command, BleProfile.CHAR_COMMAND) }
    }

    /** Sends a system command to the hub; without a link it is only logged. */
    private fun sendToHub(command: String) {
        if (!_state.value.connectionState.isLinkUp) {
            logger.log(LogType.WARN, "$command: хаб не підключено — команду пропущено (ALARM буде надіслано після підключення)")
            return
        }
        lastCommand = command
        system.setBle(system.ble.value.copy(lastCommand = command))
        viewModelScope.launch {
            val ok = connector.sendData(command, BleProfile.CHAR_COMMAND)
            if (!ok) logger.log(LogType.ERROR, "$command: не доставлено")
        }
    }

    private fun setInterference(enabled: Boolean) {
        mockConnector.interferenceEnabled = enabled
        _state.update { it.copy(interference = enabled) }
        logger.log(LogType.WARN, "Імітація перешкод: ${if (enabled) "УВІМКНЕНО (втрати 30 %, обриви)" else "вимкнено"}")
    }

    override fun onCleared() {
        connector.disconnect()
    }

    companion object {
        const val SCAN_DURATION_MS = 6_000L
    }
}
