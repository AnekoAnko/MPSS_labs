package com.example.lab6_autorotatemanager.ble

import com.example.lab6_autorotatemanager.helpers.ble.BleConnectionState
import com.example.lab6_autorotatemanager.helpers.ble.BleDevice
import com.example.lab6_autorotatemanager.helpers.ble.BleLogEntry

// State — повний стан вкладки «BLE-хаб» (ЛР №2).
data class BleState(
    val isScanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectedDevice: BleDevice? = null,
    val lastStatus: String? = null,
    val interference: Boolean = false,
    /** Текст у полі «Команда / дані». */
    val commandText: String = "",
    /** Рядки «терміналу» обміну. */
    val log: List<BleLogEntry> = emptyList()
)

// Action — дії користувача на вкладці.
sealed interface BleAction {
    /** Кнопка «Пошук пристроїв»: спершу екран запитує дозволи (Event), потім надсилає PermissionsResult. */
    data object ScanClick : BleAction
    data class PermissionsResult(val granted: Boolean) : BleAction
    data object StopScan : BleAction
    data class Connect(val device: BleDevice) : BleAction
    data object Disconnect : BleAction
    data class CommandChanged(val text: String) : BleAction
    /** Надіслати команду: готову (PING, RELAY:ON…) або null — текст із поля. */
    data class SendCommand(val command: String? = null) : BleAction
    data class SetInterference(val enabled: Boolean) : BleAction
    data object ClearLog : BleAction
}

// Event — разові дії, які може виконати лише екран.
sealed interface BleEvent {
    /** Запитати runtime-дозволи Bluetooth (діалог системи). */
    data class RequestPermissions(val permissions: Array<String>) : BleEvent
}
