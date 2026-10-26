package com.example.lab6_autorotatemanager.helpers.ble

import java.util.UUID

/** Device found during a scan (like ScanResult). */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val connectable: Boolean,
    val serviceUuids: List<UUID> = emptyList()
)

/** Connection state machine. Scanning is tracked separately by the scanner. */
enum class BleConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting…"),
    DISCOVERING("Discovering services…"),
    CONNECTED("Connected"),
    TRANSFERRING("Connected · Data transfer"),
    RECONNECTING("Reconnecting…");

    /** Whether GATT read/write is possible now. */
    val isLinkUp: Boolean get() = this == CONNECTED || this == TRANSFERRING
}

/** Notification from the device (like onCharacteristicChanged). */
data class BleNotification(val characteristic: UUID, val value: String)
