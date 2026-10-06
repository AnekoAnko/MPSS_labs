package com.example.lab5_autorotatemanager.control

/** Гілка Firebase Realtime Database, з якої додаток отримує команди. */
const val CONTROL_PATH = "control"

/**
 * Команда Cloud-to-Device — вміст вузла control/ у Firebase:
 *   control/led_status  — віртуальна лампа (true/false, "ON"/"OFF", 1/0)
 *   control/flashlight  — прожектор (необов'язково)
 *   control/siren       — сирена (необов'язково)
 *   control/threshold   — новий поріг DecisionEngine, градуси (необов'язково)
 * null — поля в базі немає, відповідний актуатор не змінюємо.
 */
data class ControlCommand(
    val ledStatus: Boolean? = null,
    val flashlight: Boolean? = null,
    val siren: Boolean? = null,
    val threshold: Float? = null
)

/** Перетворення «сирих» значень Firebase (Boolean, Number, String, Map) на команду. */
object CommandParser {

    fun fromValue(value: Any?): ControlCommand {
        val map = value as? Map<*, *> ?: return ControlCommand()
        return ControlCommand(
            ledStatus = toBool(map["led_status"]),
            flashlight = toBool(map["flashlight"]),
            siren = toBool(map["siren"]),
            threshold = toFloat(map["threshold"])
        )
    }

    fun toBool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> when (value.trim().lowercase()) {
            "true", "on", "1", "yes", "увімк", "вкл" -> true
            "false", "off", "0", "no", "вимк", "викл" -> false
            else -> null
        }
        else -> null
    }

    fun toFloat(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is String -> value.trim().replace(',', '.').toFloatOrNull()
        else -> null
    }
}

/**
 * Джерело команд із хмари. Дві реалізації:
 *  • FirebaseCommandSource   — Firebase SDK, ValueEventListener (подієве керування);
 *  • RestStreamCommandSource — REST Streaming (Server-Sent Events) через OkHttp.
 * Колбеки можуть приходити з фонового потоку.
 */
interface RemoteCommandSource {
    val mode: RemoteMode

    fun start(
        onCommand: (command: ControlCommand, raw: String) -> Unit,
        onConnection: (state: RemoteConnection, details: String?) -> Unit
    )

    fun stop()
}

/**
 * Локальна копія піддерева control/ для режиму REST Streaming.
 * Firebase надсилає події put (замінити значення за шляхом) і patch (оновити кілька дочірніх ключів);
 * шлях задається відносно вузла, на який підписалися ("/" — весь вузол, "/led_status" — одне поле).
 */
class SnapshotTree {

    var root: Any? = null
        private set

    fun put(path: String, data: Any?) {
        val keys = path.split('/').filter { it.isNotEmpty() }
        if (keys.isEmpty()) {
            root = data
            return
        }
        @Suppress("UNCHECKED_CAST")
        var node = (root as? MutableMap<String, Any?>) ?: mutableMapOf<String, Any?>().also { root = it }
        for (key in keys.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            node = (node[key] as? MutableMap<String, Any?>) ?: mutableMapOf<String, Any?>().also { node[key] = it }
        }
        if (data == null) node.remove(keys.last()) else node[keys.last()] = data
    }

    fun patch(path: String, data: Map<*, *>) {
        val base = path.trimEnd('/')
        data.forEach { (key, value) -> put("$base/$key", value) }
    }
}

/**
 * Розбір потоку Server-Sent Events рядок за рядком:
 *   event: put
 *   data: {"path":"/","data":{"led_status":true}}
 *   (порожній рядок — кінець події)
 */
class SseEventParser(private val onEvent: (event: String, data: String) -> Unit) {
    private var event: String? = null
    private val data = StringBuilder()

    fun feed(line: String) {
        when {
            line.isEmpty() -> dispatch()
            line.startsWith("event:") -> event = line.substringAfter(':').trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substringAfter(':').trim())
            }
            // рядки-коментарі (":") та невідомі поля ігноруємо
        }
    }

    private fun dispatch() {
        val name = event
        if (name != null) onEvent(name, data.toString())
        event = null
        data.setLength(0)
    }
}
