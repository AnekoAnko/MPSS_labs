package com.example.lab3_autorotatemanager.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * BLE connection abstraction. The mock can be replaced with a real BluetoothGatt implementation
 * without touching the UI or ViewModel.
 */
interface IBleConnector {

    /** Current connection state. */
    val connectionState: StateFlow<BleConnectionState>

    /** Notifications from the device. */
    val notifications: SharedFlow<BleNotification>

    /** Connect, discover services and enable notifications. Returns true on success. */
    suspend fun connect(device: BleDevice): Boolean

    /** Disconnect and release GATT resources. */
    fun disconnect()

    /** Writes a string to a characteristic (split by MTU). Returns true if delivered. */
    suspend fun sendData(data: String, characteristic: UUID = BleProfile.CHAR_COMMAND): Boolean

    /** Reads a characteristic value. */
    suspend fun readCharacteristic(characteristic: UUID): String?
}

/** BLE scanner: a cold Flow that scans while collected. */
interface IBleScanner {
    fun scan(durationMs: Long): Flow<BleDevice>
}
