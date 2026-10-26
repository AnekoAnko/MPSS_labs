package com.example.lab6_autorotatemanager.helpers

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
 * Cloud commands via the Firebase SDK (ValueEventListener on control/).
 * The SDK keeps a connection open and pushes changes. FirebaseApp is configured
 * manually, so google-services.json is not needed.
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

            // Main listener: fires with the current value, then on every change
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

            // .info/connected is true while connected to Firebase
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
            // e.g. wrong database URL or empty applicationId
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

    /** Named FirebaseApp instance configured from local.properties. */
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
        const val APP_NAME = "lab6-control"
    }
}
