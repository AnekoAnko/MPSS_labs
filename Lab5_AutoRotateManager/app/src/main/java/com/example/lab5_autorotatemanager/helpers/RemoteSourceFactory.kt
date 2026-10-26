package com.example.lab5_autorotatemanager.helpers

import android.content.Context
import com.example.lab5_autorotatemanager.BuildConfig

/**
 * Picks the command source from local.properties:
 * DB URL + APP_ID → Firebase SDK; DB URL only → REST streaming; nothing → disabled.
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
