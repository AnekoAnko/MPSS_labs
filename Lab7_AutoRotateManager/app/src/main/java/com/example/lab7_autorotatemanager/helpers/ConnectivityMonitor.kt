package com.example.lab7_autorotatemanager.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Помічник: стежить за наявністю інтернету (потрібен дозвіл ACCESS_NETWORK_STATE).
 * start()/stop() можна викликати багато разів (onStart/onStop Activity) — подвійної реєстрації не буде.
 */
class ConnectivityMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false

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

    fun start() {
        if (registered) return
        _isOnline.value = checkNow()   // поки callback був знятий, мережа могла змінитися
        cm.registerDefaultNetworkCallback(callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        cm.unregisterNetworkCallback(callback)
        registered = false
    }

    private fun checkNow(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
