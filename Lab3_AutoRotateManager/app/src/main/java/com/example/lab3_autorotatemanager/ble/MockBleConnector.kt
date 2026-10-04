package com.example.lab3_autorotatemanager.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.random.Random

/**
 * Mock-реалізація IBleConnector: імітує життєвий цикл BluetoothGatt
 * (connectGatt → onConnectionStateChange → discoverServices → requestMtu → CCCD)
 * і поведінку віртуального хаба на іншому боці радіоканалу.
 *
 * Усі затримки зроблені через suspend-функції, тому головний потік не блокується.
 */
class MockBleConnector(
    private val logger: BleLogger,
    private val scope: CoroutineScope
) : IBleConnector {

    private val _state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<BleNotification>(extraBufferCapacity = 16)
    override val notifications: SharedFlow<BleNotification> = _notifications.asSharedFlow()

    /** Режим «перешкоди в ефірі»: втрата пакетів, збої підключення та обриви зв'язку. */
    @Volatile
    var interferenceEnabled = false

    /** Android дозволяє лише одну GATT-операцію одночасно — серіалізуємо їх м'ютексом. */
    private val gattMutex = Mutex()

    private val hub = VirtualHub()
    private var device: BleDevice? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    // ---------------------------------------------------------------- connect / disconnect

    override suspend fun connect(device: BleDevice): Boolean {
        if (!device.connectable) {
            logger.log(LogType.ERROR, "${device.name}: пристрій не приймає з'єднань (non-connectable)")
            return false
        }
        if (_state.value != BleConnectionState.DISCONNECTED) return false

        this.device = device
        _state.value = BleConnectionState.CONNECTING
        logger.log(LogType.INFO, "connectGatt(${device.address}, autoConnect=false, TRANSPORT_LE)")

        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            if (attempt > 1) {
                val backoff = backoffFor(attempt - 1)
                logger.log(LogType.WARN, "Повторна спроба підключення $attempt/$MAX_CONNECT_ATTEMPTS через $backoff мс")
                delay(backoff)
            }
            if (establishLink(device)) return true
        }
        logger.log(LogType.ERROR, "Не вдалося підключитися після $MAX_CONNECT_ATTEMPTS спроб")
        this.device = null
        _state.value = BleConnectionState.DISCONNECTED
        return false
    }

    override fun disconnect() {
        if (_state.value == BleConnectionState.DISCONNECTED) return
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        heartbeatJob = null
        reconnectJob = null
        logger.log(LogType.INFO, "gatt.disconnect() → onConnectionStateChange: STATE_DISCONNECTED; gatt.close()")
        device = null
        _state.value = BleConnectionState.DISCONNECTED
    }

    /** Одна спроба: з'єднання → пошук сервісів → MTU → підписка на Notify. */
    private suspend fun establishLink(device: BleDevice): Boolean {
        delay(CONNECT_LATENCY_MS)
        if (interferenceEnabled && Random.nextFloat() < CONNECT_FAIL_PROBABILITY) {
            logger.log(LogType.ERROR, "onConnectionStateChange: status=133 (GATT_ERROR), з'єднання не встановлено")
            return false
        }
        logger.log(LogType.INFO, "onConnectionStateChange: status=0 (GATT_SUCCESS), STATE_CONNECTED")

        _state.value = BleConnectionState.DISCOVERING
        logger.log(LogType.INFO, "discoverServices()")
        delay(DISCOVERY_LATENCY_MS)
        logger.log(LogType.INFO, "onServicesDiscovered: service ${BleProfile.nameOf(BleProfile.SERVICE_AR_HUB)} {${BleProfile.SERVICE_AR_HUB}}")
        logger.log(LogType.INFO, "  ├─ STATUS      ${BleProfile.CHAR_DEVICE_STATUS} [READ, NOTIFY]")
        logger.log(LogType.INFO, "  ├─ COMMAND     ${BleProfile.CHAR_COMMAND} [WRITE]")
        logger.log(LogType.INFO, "  └─ ORIENTATION ${BleProfile.CHAR_ORIENTATION} [WRITE_NO_RESPONSE]")

        logger.log(LogType.INFO, "requestMtu(${BleProfile.ATT_MTU}) → onMtuChanged: mtu=${BleProfile.ATT_MTU}, payload ≤ ${BleProfile.MAX_PAYLOAD} B")
        logger.log(LogType.INFO, "setCharacteristicNotification(STATUS, true); writeDescriptor(CCCD = 01 00)")
        delay(150)

        _state.value = BleConnectionState.CONNECTED
        logger.log(LogType.INFO, "Підключено до ${device.name}")
        startHeartbeat()
        return true
    }

    // ---------------------------------------------------------------- read / write

    override suspend fun sendData(data: String, characteristic: UUID): Boolean {
        val charName = BleProfile.nameOf(characteristic)
        if (!_state.value.isLinkUp) {
            logger.log(LogType.WARN, "sendData(\"$data\") → [$charName]: немає з'єднання, пакет не відправлено")
            return false
        }
        val withResponse = characteristic != BleProfile.CHAR_ORIENTATION

        return gattMutex.withLock {
            _state.value = BleConnectionState.TRANSFERRING
            val bytes = data.toByteArray(Charsets.UTF_8)
            val chunks = bytes.toList().chunked(BleProfile.MAX_PAYLOAD) { it.toByteArray() }

            var delivered = true
            for ((index, chunk) in chunks.withIndex()) {
                if (!writeChunk(charName, chunk, index, chunks.size, withResponse)) {
                    delivered = false
                    break
                }
            }
            if (_state.value == BleConnectionState.TRANSFERRING) {
                _state.value = BleConnectionState.CONNECTED
            }
            if (delivered) deliverToHub(characteristic, data)
            delivered
        }
    }

    /** Запис одного пакета з повторними спробами (exponential backoff). */
    private suspend fun writeChunk(
        charName: String,
        chunk: ByteArray,
        index: Int,
        total: Int,
        withResponse: Boolean
    ): Boolean {
        val type = if (withResponse) "WRITE_TYPE_DEFAULT" else "WRITE_TYPE_NO_RESPONSE"
        for (attempt in 1..MAX_WRITE_ATTEMPTS) {
            val retry = if (attempt > 1) " (спроба $attempt)" else ""
            logger.log(
                LogType.TX,
                "[$charName] пакет ${index + 1}/$total, ${chunk.size} B, $type$retry: ${chunk.toHex()} \"${String(chunk, Charsets.UTF_8)}\""
            )
            delay(WRITE_LATENCY_MS)

            if (!_state.value.isLinkUp) return false
            val lost = interferenceEnabled && Random.nextFloat() < PACKET_LOSS_PROBABILITY

            if (!withResponse) {
                // Без підтвердження додаток не дізнається про втрату — лише mock бачить «ефір»
                if (lost) logger.log(LogType.WARN, "[mock] пакет загублено в ефірі (Write Without Response не має ACK)")
                return true
            }
            if (!lost) {
                logger.log(LogType.INFO, "onCharacteristicWrite: [$charName] status=GATT_SUCCESS")
                return true
            }
            val backoff = backoffFor(attempt)
            logger.log(LogType.WARN, "onCharacteristicWrite: немає підтвердження від пристрою, повтор через $backoff мс")
            delay(backoff)
        }
        logger.log(LogType.ERROR, "[$charName] пакет ${index + 1}/$total не доставлено після $MAX_WRITE_ATTEMPTS спроб")
        return false
    }

    override suspend fun readCharacteristic(characteristic: UUID): String? {
        val charName = BleProfile.nameOf(characteristic)
        if (!_state.value.isLinkUp) {
            logger.log(LogType.WARN, "readCharacteristic([$charName]): немає з'єднання")
            return null
        }
        return gattMutex.withLock {
            logger.log(LogType.TX, "readCharacteristic([$charName])")
            delay(WRITE_LATENCY_MS)
            val value = if (characteristic == BleProfile.CHAR_DEVICE_STATUS) hub.status() else null
            logger.log(LogType.RX, "onCharacteristicRead: [$charName] \"$value\"")
            value
        }
    }

    // ---------------------------------------------------------------- віртуальний пристрій

    /** Пристрій обробляє отримані дані й відповідає через Notify характеристики STATUS. */
    private fun deliverToHub(characteristic: UUID, data: String) {
        scope.launch {
            delay(HUB_RESPONSE_DELAY_MS)
            val response = hub.handle(characteristic, data) ?: return@launch
            notifyFromHub(response)
        }
    }

    private suspend fun notifyFromHub(value: String) {
        if (!_state.value.isLinkUp) return
        logger.log(LogType.RX, "[STATUS] onCharacteristicChanged: \"$value\"")
        _notifications.emit(BleNotification(BleProfile.CHAR_DEVICE_STATUS, value))
    }

    /** Пристрій кожні 5 с надсилає свій статус; у режимі перешкод зв'язок може обірватися. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (interferenceEnabled && Random.nextFloat() < LINK_LOSS_PROBABILITY) {
                    onLinkLost()
                    return@launch
                }
                hub.tick()
                notifyFromHub(hub.status())
            }
        }
    }

    /** Обрив зв'язку: переходимо в RECONNECTING і пробуємо відновити з'єднання. */
    private fun onLinkLost() {
        val target = device ?: return
        logger.log(LogType.ERROR, "onConnectionStateChange: status=8 (GATT_CONN_TIMEOUT), зв'язок втрачено")
        _state.value = BleConnectionState.RECONNECTING
        reconnectJob = scope.launch {
            for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
                val backoff = backoffFor(attempt)
                logger.log(LogType.WARN, "Автоперепідключення $attempt/$MAX_CONNECT_ATTEMPTS через $backoff мс")
                delay(backoff)
                if (establishLink(target)) return@launch
            }
            logger.log(LogType.ERROR, "Відновити з'єднання не вдалося")
            disconnect()
        }
    }

    /** 400, 800, 1600 мс… */
    private fun backoffFor(attempt: Int): Long = BASE_BACKOFF_MS shl (attempt - 1)

    companion object {
        const val CONNECT_LATENCY_MS = 1_200L
        const val DISCOVERY_LATENCY_MS = 600L
        const val WRITE_LATENCY_MS = 80L
        const val HUB_RESPONSE_DELAY_MS = 150L
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val BASE_BACKOFF_MS = 400L

        const val MAX_CONNECT_ATTEMPTS = 3
        const val MAX_WRITE_ATTEMPTS = 3

        const val CONNECT_FAIL_PROBABILITY = 0.3f
        const val PACKET_LOSS_PROBABILITY = 0.3f
        const val LINK_LOSS_PROBABILITY = 0.2f
    }
}

/** Логіка «прошивки» віртуального хаба (розумного реле). */
private class VirtualHub {
    private var relayOn = false
    private var battery = 92
    private var orientation = "UNKNOWN"
    private var ticks = 0

    fun status(): String = "relay=${if (relayOn) "ON" else "OFF"};bat=$battery;orient=$orientation"

    fun tick() {
        ticks++
        if (ticks % 6 == 0 && battery > 5) battery--
    }

    fun handle(characteristic: UUID, data: String): String? = when (characteristic) {
        BleProfile.CHAR_COMMAND -> handleCommand(data.trim().uppercase())
        BleProfile.CHAR_ORIENTATION -> {
            orientation = data.removePrefix("ORIENT:")
            status()
        }
        else -> null
    }

    private fun handleCommand(cmd: String): String = when (cmd) {
        "PING" -> "PONG"
        "RELAY:ON" -> { relayOn = true; "OK:RELAY=ON" }
        "RELAY:OFF" -> { relayOn = false; "OK:RELAY=OFF" }
        "GET_STATUS" -> status()
        else -> "ERR:UNKNOWN_CMD"
    }
}
