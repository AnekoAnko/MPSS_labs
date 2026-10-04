package com.example.lab3_autorotatemanager.ble

import java.util.UUID

/**
 * GATT-профіль віртуального IoT-пристрою AR-Hub (Auto-Rotate Hub).
 *
 * Для власного сервісу використано 128-бітні UUID зі спільною основою
 * b1e5XXXX-0a46-4c5e-8f2b-2026a0b1c0de (XXXX — номер атрибута).
 * 16-бітні UUID на основі 0000XXXX-0000-1000-8000-00805f9b34fb
 * зарезервовані Bluetooth SIG, тому для своїх характеристик їх не беремо.
 */
object BleProfile {

    /** Ім'я та MAC-адреса віртуального пристрою. */
    const val DEVICE_NAME = "AR-Hub-46"
    const val DEVICE_ADDRESS = "C0:FF:EE:46:02:01"

    /** Первинний сервіс AR Hub. */
    val SERVICE_AR_HUB: UUID = UUID.fromString("b1e50001-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Статус пристрою (Read, Notify): хаб → додаток. */
    val CHAR_DEVICE_STATUS: UUID = UUID.fromString("b1e50002-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Команди (Write With Response): додаток → хаб. */
    val CHAR_COMMAND: UUID = UUID.fromString("b1e50003-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Орієнтація з акселерометра ЛР №1 (Write Without Response): додаток → хаб. */
    val CHAR_ORIENTATION: UUID = UUID.fromString("b1e50004-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Стандартний дескриптор CCCD (вмикає Notify на стороні пристрою). */
    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** ATT MTU після requestMtu(); корисне навантаження одного пакета = MTU − 3 байти заголовка ATT. */
    const val ATT_MTU = 64
    const val MAX_PAYLOAD = ATT_MTU - 3

    /** Коротка назва атрибута для логів. */
    fun nameOf(uuid: UUID): String = when (uuid) {
        SERVICE_AR_HUB -> "AR_HUB_SERVICE"
        CHAR_DEVICE_STATUS -> "STATUS"
        CHAR_COMMAND -> "COMMAND"
        CHAR_ORIENTATION -> "ORIENTATION"
        DESCRIPTOR_CCCD -> "CCCD"
        else -> uuid.toString()
    }
}
