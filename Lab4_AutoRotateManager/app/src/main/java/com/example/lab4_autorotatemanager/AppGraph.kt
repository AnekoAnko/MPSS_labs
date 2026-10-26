package com.example.lab4_autorotatemanager

import android.app.Application
import com.example.lab4_autorotatemanager.helpers.SensorHub
import com.example.lab4_autorotatemanager.helpers.deviceIdOf
import com.example.lab4_autorotatemanager.repository.CloudModule
import com.example.lab4_autorotatemanager.repository.SensorRepository
import com.example.lab4_autorotatemanager.repository.SystemStateRepository

/** Single instances of repositories and shared helpers for the whole app. */
object AppGraph {

    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
    }

    /** Shared accelerometer stream (filled by MonitorViewModel). */
    val sensorHub: SensorHub by lazy { SensorHub() }

    /** Cloud repository. */
    val sensorRepository: SensorRepository by lazy {
        SensorRepository(
            api = CloudModule.createApi(BuildConfig.FIREBASE_DB_URL),
            authToken = BuildConfig.FIREBASE_AUTH.ifBlank { null },
            deviceId = deviceId
        )
    }

    /** Shared state: orientation, threshold, alarm. */
    val systemState: SystemStateRepository by lazy { SystemStateRepository() }

    /** "AR-" + 8 chars of ANDROID_ID. */
    val deviceId: String by lazy { deviceIdOf(app) }
}

/** Application class: created before any Activity, initializes AppGraph. */
class AutoRotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
