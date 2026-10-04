package com.example.lab3_autorotatemanager.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Bluetooth Interface Layer — абстракція над BLE-з'єднанням.
 * UI та ViewModel працюють лише з цим інтерфейсом, тому Mock-реалізацію
 * можна замінити на справжню (BluetoothGatt) без змін в інших шарах.
 */
interface IBleConnector {

    /** Поточний стан з'єднання (для індикатора Connected/Disconnected). */
    val connectionState: StateFlow<BleConnectionState>

    /** Потік повідомлень Notify від пристрою. */
    val notifications: SharedFlow<BleNotification>

    /** Підключення + пошук сервісів + увімкнення Notify. Повертає true при успіху. */
    suspend fun connect(device: BleDevice): Boolean

    /** Розрив з'єднання і звільнення ресурсів GATT. */
    fun disconnect()

    /** Запис рядка в характеристику (з фрагментацією за MTU). Повертає true, якщо доставлено. */
    suspend fun sendData(data: String, characteristic: UUID = BleProfile.CHAR_COMMAND): Boolean

    /** Читання значення характеристики (Read). */
    suspend fun readCharacteristic(characteristic: UUID): String?
}

/** Сканер BLE-пристроїв: холодний потік, сканування триває, доки його збирають. */
interface IBleScanner {
    fun scan(durationMs: Long): Flow<BleDevice>
}
