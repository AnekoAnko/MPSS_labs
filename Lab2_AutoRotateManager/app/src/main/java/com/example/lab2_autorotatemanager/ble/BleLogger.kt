package com.example.lab2_autorotatemanager.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class LogType(val tag: String) {
    INFO("INFO"),
    TX("TX →"),
    RX("RX ←"),
    WARN("WARN"),
    ERROR("ERR ")
}

data class BleLogEntry(val time: String, val type: LogType, val message: String) {
    override fun toString() = "$time [${type.tag}] $message"
}

/**
 * Журнал обміну: кожен запис одночасно йде в Logcat (тег BLE_MOCK)
 * і в StateFlow, який відображає «термінал» у додатку.
 */
class BleLogger(private val maxEntries: Int = 300) {

    private val _entries = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val entries: StateFlow<List<BleLogEntry>> = _entries.asStateFlow()

    fun log(type: LogType, message: String) {
        val entry = BleLogEntry(LocalTime.now().format(TIME_FORMAT), type, message)
        val line = "[${type.tag}] $message"
        when (type) {
            LogType.ERROR -> Log.e(TAG, line)
            LogType.WARN -> Log.w(TAG, line)
            LogType.TX, LogType.RX -> Log.i(TAG, line)
            LogType.INFO -> Log.d(TAG, line)
        }
        _entries.update { (it + entry).takeLast(maxEntries) }
    }

    fun clear() = _entries.update { emptyList() }

    companion object {
        const val TAG = "BLE_MOCK"
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

/** Байти у вигляді HEX-рядка: "50 49 4E 47". */
fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
