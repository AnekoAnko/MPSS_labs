package com.example.lab7_autorotatemanager.helpers.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context

/** Checks the Bluetooth adapter and lists runtime permissions. */
class BluetoothEnvironment(private val context: Context) {

    fun isAdapterAvailable(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter != null

    companion object {
        /** Bluetooth runtime permissions (Android 12+). */
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
