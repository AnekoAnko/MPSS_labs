package com.example.lab4_autorotatemanager.ble

import com.example.lab4_autorotatemanager.helpers.ble.BleConnectionState
import com.example.lab4_autorotatemanager.helpers.ble.BleDevice
import com.example.lab4_autorotatemanager.helpers.ble.BleLogEntry

// State: the BLE hub tab.
data class BleState(
    val isScanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectedDevice: BleDevice? = null,
    val lastStatus: String? = null,
    val interference: Boolean = false,
    /** Text in the command field. */
    val commandText: String = "",
    /** Terminal lines. */
    val log: List<BleLogEntry> = emptyList()
)

// Action: user intents on this tab.
sealed interface BleAction {
    /** Scan button: the screen requests permissions first, then sends PermissionsResult. */
    data object ScanClick : BleAction
    data class PermissionsResult(val granted: Boolean) : BleAction
    data object StopScan : BleAction
    data class Connect(val device: BleDevice) : BleAction
    data object Disconnect : BleAction
    data class CommandChanged(val text: String) : BleAction
    /** Send a preset command (PING, RELAY:ON…) or the text field when null. */
    data class SendCommand(val command: String? = null) : BleAction
    data class SetInterference(val enabled: Boolean) : BleAction
    data object ClearLog : BleAction
}

// Event: one-off actions only the screen can perform.
sealed interface BleEvent {
    /** Ask for Bluetooth runtime permissions. */
    data class RequestPermissions(val permissions: Array<String>) : BleEvent
}
