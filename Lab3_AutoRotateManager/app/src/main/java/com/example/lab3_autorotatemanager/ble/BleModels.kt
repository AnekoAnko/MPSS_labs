package com.example.lab3_autorotatemanager.ble

import java.util.UUID

/** Знайдений під час сканування пристрій (аналог android.bluetooth.le.ScanResult). */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val connectable: Boolean,
    val serviceUuids: List<UUID> = emptyList()
)

/** Стани з'єднання (скінченний автомат). Стан Scanning веде сканер, див. BleUiState.isScanning. */
enum class BleConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting…"),
    DISCOVERING("Discovering services…"),
    CONNECTED("Connected"),
    TRANSFERRING("Connected · Data transfer"),
    RECONNECTING("Reconnecting…");

    /** Чи можна зараз виконувати GATT-операції (read/write). */
    val isLinkUp: Boolean get() = this == CONNECTED || this == TRANSFERRING
}

/** Повідомлення від пристрою (аналог onCharacteristicChanged). */
data class BleNotification(val characteristic: UUID, val value: String)
