package com.example.lab2_autorotatemanager.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlin.random.Random

/**
 * Імітація BluetoothLeScanner: через 2 секунди «знаходить» віртуальний AR-Hub,
 * трохи згодом — сторонній маяк, який не підтримує підключення.
 */
class MockBleScanner(
    private val logger: BleLogger,
    private val discoveryDelayMs: Long = 2_000
) : IBleScanner {

    override fun scan(durationMs: Long): Flow<BleDevice> = flow {
        logger.log(LogType.INFO, "startScan(): SCAN_MODE_LOW_LATENCY, тривалість ${durationMs / 1000} с")

        delay(discoveryDelayMs)
        val hub = BleDevice(
            name = BleProfile.DEVICE_NAME,
            address = BleProfile.DEVICE_ADDRESS,
            rssi = Random.nextInt(-66, -52),
            connectable = true,
            serviceUuids = listOf(BleProfile.SERVICE_AR_HUB)
        )
        logger.log(
            LogType.INFO,
            "onScanResult: ${hub.name} [${hub.address}] RSSI=${hub.rssi} dBm, service=${BleProfile.SERVICE_AR_HUB}"
        )
        emit(hub)

        delay(700)
        val beacon = BleDevice(
            name = "BLE-Beacon-07",
            address = "D4:36:39:0A:17:5E",
            rssi = Random.nextInt(-90, -78),
            connectable = false
        )
        logger.log(LogType.INFO, "onScanResult: ${beacon.name} [${beacon.address}] RSSI=${beacon.rssi} dBm (non-connectable)")
        emit(beacon)

        delay((durationMs - discoveryDelayMs - 700).coerceAtLeast(0))
    }.onCompletion { cause ->
        // onCompletion спрацьовує і при таймауті, і при скасуванні корутини
        val reason = if (cause == null) "таймаут сканування" else "зупинено"
        logger.log(LogType.INFO, "stopScan(): $reason")
    }
}
