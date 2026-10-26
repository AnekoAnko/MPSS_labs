package com.example.lab4_autorotatemanager.helpers

/** Firebase node the app reads commands from. */
const val CONTROL_PATH = "control"

/** How cloud commands are received. */
enum class RemoteMode(val label: String) {
    SDK("Firebase SDK · ValueEventListener"),
    SSE("REST Streaming (SSE) · OkHttp"),
    NONE("не налаштовано")
}

/** Cloud-to-device channel state. */
enum class RemoteConnection(val label: String) {
    DISCONNECTED("відключено"),
    CONNECTING("підключення…"),
    CONNECTED("підключено"),
    ERROR("помилка")
}

/**
 * Contents of control/ in Firebase: led_status (virtual lamp), flashlight, siren, threshold.
 * null means the field is missing and the actuator is left unchanged.
 */
data class ControlCommand(
    val ledStatus: Boolean? = null,
    val flashlight: Boolean? = null,
    val siren: Boolean? = null,
    val threshold: Float? = null
)

/** Converts raw Firebase values (Boolean, Number, String, Map) into a command. */
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
 * Source of cloud commands: FirebaseCommandSource (SDK) or RestStreamCommandSource (SSE).
 * Callbacks may arrive on a background thread.
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
 * Local copy of control/ for REST streaming.
 * Applies put (replace at path) and patch (update child keys) events.
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

/** Parses a Server-Sent Events stream line by line (event:, data:, blank line ends an event). */
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
            // comment lines (":") and unknown fields are ignored
        }
    }

    private fun dispatch() {
        val name = event
        if (name != null) onEvent(name, data.toString())
        event = null
        data.setLength(0)
    }
}
