package com.example.lab3_autorotatemanager.ble

import java.util.UUID

/**
 * GATT profile of the virtual AR-Hub device. Custom 128-bit UUIDs share the base
 * b1e5XXXX-0a46-4c5e-8f2b-2026a0b1c0de (16-bit UUIDs are reserved by the Bluetooth SIG).
 */
object BleProfile {

    /** Name and MAC address of the virtual device. */
    const val DEVICE_NAME = "AR-Hub-46"
    const val DEVICE_ADDRESS = "C0:FF:EE:46:02:01"

    /** Primary AR Hub service. */
    val SERVICE_AR_HUB: UUID = UUID.fromString("b1e50001-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Device status (Read, Notify): hub → app. */
    val CHAR_DEVICE_STATUS: UUID = UUID.fromString("b1e50002-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Commands (Write With Response): app → hub. */
    val CHAR_COMMAND: UUID = UUID.fromString("b1e50003-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Orientation (Write Without Response): app → hub. */
    val CHAR_ORIENTATION: UUID = UUID.fromString("b1e50004-0a46-4c5e-8f2b-2026a0b1c0de")

    /** Standard CCCD descriptor (enables notifications). */
    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** ATT MTU after requestMtu(); payload per packet = MTU − 3. */
    const val ATT_MTU = 64
    const val MAX_PAYLOAD = ATT_MTU - 3

    /** Short attribute name for logs. */
    fun nameOf(uuid: UUID): String = when (uuid) {
        SERVICE_AR_HUB -> "AR_HUB_SERVICE"
        CHAR_DEVICE_STATUS -> "STATUS"
        CHAR_COMMAND -> "COMMAND"
        CHAR_ORIENTATION -> "ORIENTATION"
        DESCRIPTOR_CCCD -> "CCCD"
        else -> uuid.toString()
    }
}
