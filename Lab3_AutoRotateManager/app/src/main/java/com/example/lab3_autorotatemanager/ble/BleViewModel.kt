package com.example.lab3_autorotatemanager.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Повний стан екрана BLE, який UI отримує через StateFlow. */
data class BleUiState(
    val isScanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectedDevice: BleDevice? = null,
    val lastStatus: String? = null,
    val interference: Boolean = false
)

class BleViewModel : ViewModel() {

    val logger = BleLogger()
    private val scanner: IBleScanner = MockBleScanner(logger)
    private val mockConnector = MockBleConnector(logger, viewModelScope)
    private val connector: IBleConnector = mockConnector

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    /** Рядки «терміналу». */
    val log: StateFlow<List<BleLogEntry>> = logger.entries

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var lastOrientation: String? = null

    init {
        // Стан з'єднання з шару Bluetooth → стан UI
        viewModelScope.launch {
            connector.connectionState.collect { state ->
                _uiState.update {
                    it.copy(
                        connectionState = state,
                        connectedDevice = if (state == BleConnectionState.DISCONNECTED) null else it.connectedDevice,
                        lastStatus = if (state == BleConnectionState.DISCONNECTED) null else it.lastStatus
                    )
                }
            }
        }
        // Notify від пристрою → останній статус
        viewModelScope.launch {
            connector.notifications.collect { n -> _uiState.update { it.copy(lastStatus = n.value) } }
        }
    }

    fun onEnvironmentChecked(adapterAvailable: Boolean, permissionsGranted: Boolean) {
        logger.log(
            LogType.INFO,
            "BluetoothAdapter: ${if (adapterAvailable) "є" else "відсутній"}; " +
                "дозволи: ${if (permissionsGranted) "надано" else "не надано"}; режим: Mock (IBleConnector → MockBleConnector)"
        )
    }

    fun startScan() {
        if (scanJob?.isActive == true || _uiState.value.connectionState != BleConnectionState.DISCONNECTED) return
        _uiState.update { it.copy(isScanning = true, devices = emptyList()) }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan(SCAN_DURATION_MS).collect { device ->
                    _uiState.update { s ->
                        if (s.devices.any { it.address == device.address }) s
                        else s.copy(devices = s.devices + device)
                    }
                }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun connect(device: BleDevice) {
        if (_uiState.value.connectionState != BleConnectionState.DISCONNECTED) return
        stopScan() // сканування під час підключення знижує стабільність з'єднання
        _uiState.update { it.copy(connectedDevice = device) }
        connectJob = viewModelScope.launch {
            if (connector.connect(device)) {
                connector.readCharacteristic(BleProfile.CHAR_DEVICE_STATUS)?.let { status ->
                    _uiState.update { it.copy(lastStatus = status) }
                }
                lastOrientation?.let { connector.sendData("ORIENT:$it", BleProfile.CHAR_ORIENTATION) }
            } else {
                _uiState.update { it.copy(connectedDevice = null) }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connector.disconnect()
    }

    fun sendCommand(text: String) {
        val command = text.trim()
        if (command.isEmpty()) return
        viewModelScope.launch { connector.sendData(command, BleProfile.CHAR_COMMAND) }
    }

    /** Викликається з MainActivity, коли Auto-Rotate Manager (ЛР №1) визначив новий стан. */
    fun onOrientationChanged(orientation: String) {
        lastOrientation = orientation
        if (_uiState.value.connectionState.isLinkUp) {
            viewModelScope.launch { connector.sendData("ORIENT:$orientation", BleProfile.CHAR_ORIENTATION) }
        }
    }

    fun setInterference(enabled: Boolean) {
        mockConnector.interferenceEnabled = enabled
        _uiState.update { it.copy(interference = enabled) }
        logger.log(LogType.WARN, "Імітація перешкод: ${if (enabled) "УВІМКНЕНО (втрати 30 %, обриви)" else "вимкнено"}")
    }

    fun clearLog() = logger.clear()

    override fun onCleared() {
        connector.disconnect()
    }

    companion object {
        const val SCAN_DURATION_MS = 6_000L
    }
}
