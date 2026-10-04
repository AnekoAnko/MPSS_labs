package com.example.lab3_autorotatemanager.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Стежить за наявністю інтернету (потрібен дозвіл ACCESS_NETWORK_STATE). */
class ConnectivityMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(checkNow())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun onLost(network: Network) {
            _isOnline.value = false
        }
    }

    fun start() = cm.registerDefaultNetworkCallback(callback)

    fun stop() = cm.unregisterNetworkCallback(callback)

    private fun checkNow(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
