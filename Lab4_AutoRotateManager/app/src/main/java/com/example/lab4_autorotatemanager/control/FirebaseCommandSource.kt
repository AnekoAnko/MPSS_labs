package com.example.lab4_autorotatemanager.control

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Дистанційне керування через Firebase SDK: ValueEventListener на гілку control/.
 * SDK тримає постійне WebSocket-з'єднання, і сервер сам «штовхає» зміни в додаток
 * (подієве керування, без опитування).
 *
 * FirebaseApp ініціалізується вручну (FirebaseOptions), тому google-services.json
 * і плагін com.google.gms.google-services не потрібні.
 */
class FirebaseCommandSource(
    private val context: Context,
    private val databaseUrl: String,
    private val applicationId: String,
    private val apiKey: String?,
    private val path: String = CONTROL_PATH
) : RemoteCommandSource {

    override val mode = RemoteMode.SDK

    private var controlRef: DatabaseReference? = null
    private var controlListener: ValueEventListener? = null
    private var connectedRef: DatabaseReference? = null
    private var connectedListener: ValueEventListener? = null

    override fun start(
        onCommand: (command: ControlCommand, raw: String) -> Unit,
        onConnection: (state: RemoteConnection, details: String?) -> Unit
    ) {
        try {
            onConnection(RemoteConnection.CONNECTING, "SDK, ${path}/")
            val database = FirebaseDatabase.getInstance(obtainApp(), databaseUrl)

            // 1) Основний слухач: викликається одразу з поточним значенням і далі — на кожну зміну
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val value = snapshot.value
                    Log.d(TAG, "onDataChange(${snapshot.key}) = $value")
                    onCommand(CommandParser.fromValue(value), value.toString())
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "onCancelled: ${error.message}")
                    onConnection(RemoteConnection.ERROR, error.message)
                }
            }
            val ref = database.getReference(path)
            ref.addValueEventListener(listener)
            controlRef = ref
            controlListener = listener

            // 2) Службовий вузол .info/connected: true, коли є з'єднання із сервером Firebase
            val infoListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) == true
                    onConnection(
                        if (connected) RemoteConnection.CONNECTED else RemoteConnection.CONNECTING,
                        if (connected) "SDK, слухач на ${path}/" else "SDK, очікування мережі"
                    )
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            val info = database.getReference(".info/connected")
            info.addValueEventListener(infoListener)
            connectedRef = info
            connectedListener = infoListener
        } catch (e: Exception) {
            // Неправильний URL бази, порожній applicationId тощо
            Log.e(TAG, "Firebase SDK не ініціалізовано: ${e.message}")
            onConnection(RemoteConnection.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun stop() {
        controlListener?.let { controlRef?.removeEventListener(it) }
        connectedListener?.let { connectedRef?.removeEventListener(it) }
        controlListener = null
        connectedListener = null
    }

    /** Окремий іменований екземпляр FirebaseApp з параметрами з local.properties. */
    private fun obtainApp(): FirebaseApp =
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(applicationId)
                    .setDatabaseUrl(databaseUrl)
                    .apply { if (!apiKey.isNullOrBlank()) setApiKey(apiKey) }
                    .build(),
                APP_NAME
            )

    companion object {
        const val TAG = "RemoteControl"
        const val APP_NAME = "lab4-control"
    }
}
