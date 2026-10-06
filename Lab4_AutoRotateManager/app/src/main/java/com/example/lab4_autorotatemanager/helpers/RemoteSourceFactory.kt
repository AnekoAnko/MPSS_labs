package com.example.lab4_autorotatemanager.helpers

import android.content.Context
import com.example.lab4_autorotatemanager.BuildConfig

/**
 * Вибір джерела команд за вмістом local.properties:
 *  • задано FIREBASE_DB_URL і FIREBASE_APP_ID → Firebase SDK (ValueEventListener);
 *  • задано лише FIREBASE_DB_URL               → REST Streaming (SSE);
 *  • нічого не задано                          → дистанційне керування вимкнене.
 */
object RemoteSourceFactory {

    fun create(context: Context): RemoteCommandSource? {
        val url = BuildConfig.FIREBASE_DB_URL
        if (url.isBlank()) return null
        val appId = BuildConfig.FIREBASE_APP_ID
        return if (appId.isNotBlank()) {
            FirebaseCommandSource(context.applicationContext, url, appId, BuildConfig.FIREBASE_API_KEY.ifBlank { null })
        } else {
            RestStreamCommandSource(url, BuildConfig.FIREBASE_AUTH.ifBlank { null })
        }
    }
}
