package com.example.lab4_autorotatemanager.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lab4_autorotatemanager.AppGraph
import com.example.lab4_autorotatemanager.helpers.ble.BleConnectionState
import com.example.lab4_autorotatemanager.helpers.ble.BleDevice
import com.example.lab4_autorotatemanager.helpers.ble.BleLogger
import com.example.lab4_autorotatemanager.helpers.ble.BleProfile
import com.example.lab4_autorotatemanager.helpers.ble.BluetoothEnvironment
import com.example.lab4_autorotatemanager.helpers.ble.IBleConnector
import com.example.lab4_autorotatemanager.helpers.ble.IBleScanner
import com.example.lab4_autorotatemanager.helpers.ble.LogType
import com.example.lab4_autorotatemanager.helpers.ble.MockBleConnector
import com.example.lab4_autorotatemanager.helpers.ble.MockBleScanner
import com.example.lab4_autorotatemanager.repository.SystemStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** BLE hub tab. Talks to Bluetooth only through IBleScanner / IBleConnector (mock AR-Hub-46). */
class BleViewModel(
    private val environment: BluetoothEnvironment = BluetoothEnvironment(AppGraph.app),
    private val system: SystemStateRepository = AppGraph.systemState
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
