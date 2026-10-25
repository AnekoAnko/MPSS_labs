package com.example.lab7_autorotatemanager.helpers.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context

/** Помічник: перевірка Bluetooth-адаптера і перелік runtime-дозволів для сканування. */
class BluetoothEnvironment(private val context: Context) {

    fun isAdapterAvailable(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter != null

    companion object {
        /** Runtime-дозволи Bluetooth (Android 12+). Запитує екран, результат приходить як Action. */
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
