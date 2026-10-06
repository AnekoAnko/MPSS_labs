package com.example.lab5_autorotatemanager.control

import android.util.Log
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Резервний варіант дистанційного керування без Firebase SDK:
 * REST Streaming — GET control.json з заголовком Accept: text/event-stream.
 * Сервер тримає HTTP-з'єднання відкритим і надсилає Server-Sent Events:
 *   put / patch — дані змінилися;  keep-alive — «пульс» кожні ~30 с;
 *   cancel — правила безпеки забороняють читання;  auth_revoked — токен недійсний.
 * При розриві — повторне підключення з експоненційною затримкою 1 → 2 → 4 … 30 с.
 */
class RestStreamCommandSource(
    baseUrl: String,
    authToken: String?,
    path: String = CONTROL_PATH
) : RemoteCommandSource {

    override val mode = RemoteMode.SSE

    private val url = baseUrl.trimEnd('/') + "/" + path + ".json" +
        (if (authToken.isNullOrBlank()) "" else "?auth=$authToken")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // потік безстроковий: тайм-аут читання вимкнено
        .build()

    private val gson = Gson()

    @Volatile private var running = false
    @Volatile private var call: Call? = null
    private var worker: Thread? = null

    override fun start(
        onCommand: (command: ControlCommand, raw: String) -> Unit,
        onConnection: (state: RemoteConnection, details: String?) -> Unit
    ) {
        if (running) return
        running = true
        worker = Thread({ loop(onCommand, onConnection) }, "control-sse").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        call?.cancel()
        worker?.interrupt()
    }

    private fun loop(
        onCommand: (ControlCommand, String) -> Unit,
        onConnection: (RemoteConnection, String?) -> Unit
    ) {
        var backoffMs = 1_000L
        while (running) {
            try {
                listenOnce(onCommand, onConnection)
                backoffMs = 1_000L
            } catch (e: IOException) {
                if (!running) break
                Log.w(TAG, "Потік розірвано: ${e.message}; повтор через $backoffMs мс")
                onConnection(RemoteConnection.ERROR, "${e.message}; повтор через ${backoffMs / 1000} с")
            }
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        onConnection(RemoteConnection.DISCONNECTED, null)
    }

    private fun listenOnce(
        onCommand: (ControlCommand, String) -> Unit,
        onConnection: (RemoteConnection, String?) -> Unit
    ) {
        onConnection(RemoteConnection.CONNECTING, "SSE, ${url.substringBefore('?')}")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .build()
        val newCall = client.newCall(request)
        call = newCall

        newCall.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            onConnection(RemoteConnection.CONNECTED, "SSE, слухач на $CONTROL_PATH/")

            val tree = SnapshotTree()
            val parser = SseEventParser { event, data -> handleEvent(event, data, tree, onCommand) }
            val source = response.body?.source() ?: throw IOException("Порожня відповідь")
            while (running) {
                val line = source.readUtf8Line() ?: break
                parser.feed(line)
            }
        }
        if (running) throw IOException("Сервер закрив потік")
    }

    private fun handleEvent(
        event: String,
        data: String,
        tree: SnapshotTree,
        onCommand: (ControlCommand, String) -> Unit
    ) {
        when (event) {
            "put", "patch" -> {
                val message = gson.fromJson(data, Map::class.java) ?: return
                val path = message["path"] as? String ?: "/"
                val payload = message["data"]
                if (event == "put") tree.put(path, payload)
                else (payload as? Map<*, *>)?.let { tree.patch(path, it) }
                Log.d(TAG, "$event $path → ${gson.toJson(tree.root)}")
                onCommand(CommandParser.fromValue(tree.root), gson.toJson(tree.root))
            }
            "keep-alive" -> Unit
            "cancel" -> throw IOException("cancel: правила безпеки забороняють читання $CONTROL_PATH/")
            "auth_revoked" -> throw IOException("auth_revoked: токен доступу недійсний")
        }
    }

    companion object {
        const val TAG = "RemoteControl"
    }
}
