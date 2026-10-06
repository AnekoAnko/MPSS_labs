package com.example.lab4_autorotatemanager.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/** Стабільний ідентифікатор пристрою: "AR-" + перші 8 символів ANDROID_ID. */
@SuppressLint("HardwareIds")
fun deviceIdOf(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    return "AR-" + (androidId ?: "unknown").take(8).uppercase()
}
