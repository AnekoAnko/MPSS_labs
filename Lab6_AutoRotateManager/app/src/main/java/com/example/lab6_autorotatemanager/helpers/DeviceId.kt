package com.example.lab6_autorotatemanager.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/** Stable device id: "AR-" + first 8 chars of ANDROID_ID. */
@SuppressLint("HardwareIds")
fun deviceIdOf(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    return "AR-" + (androidId ?: "unknown").take(8).uppercase()
}
