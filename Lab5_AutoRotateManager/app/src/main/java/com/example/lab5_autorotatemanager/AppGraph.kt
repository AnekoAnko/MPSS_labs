package com.example.lab5_autorotatemanager

import android.app.Application
import com.example.lab5_autorotatemanager.helpers.SensorHub
import com.example.lab5_autorotatemanager.helpers.deviceIdOf
import com.example.lab5_autorotatemanager.repository.AppDatabase
import com.example.lab5_autorotatemanager.repository.CloudModule
import com.example.lab5_autorotatemanager.repository.SensorRepository
import com.example.lab5_autorotatemanager.repository.SystemStateRepository

/**
 * Один екземпляр репозиторіїв і спільних помічників на весь додаток.
 * Якби кожна ViewModel створювала свій репозиторій, дані на різних екранах «роз'їхалися» б.
 */
object AppGraph {

    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
    }

    /** Спільний потік даних акселерометра (його наповнює MonitorViewModel). */
    val sensorHub: SensorHub by lazy { SensorHub() }

    /** Хмара (ЛР №3–4) + локальна БД Room (ЛР №5). */
    val sensorRepository: SensorRepository by lazy {
        SensorRepository(
            api = CloudModule.createApi(BuildConfig.FIREBASE_DB_URL),
            authToken = BuildConfig.FIREBASE_AUTH.ifBlank { null },
            dao = AppDatabase.getInstance(app).sensorDao()
        )
    }

    /** Спільний стан: орієнтація, поріг, тривога (його читає й аналітика ЛР №5). */
    val systemState: SystemStateRepository by lazy { SystemStateRepository() }

    /** "AR-" + 8 символів ANDROID_ID. */
    val deviceId: String by lazy { deviceIdOf(app) }
}

/** Клас Application: створюється раніше за будь-яку Activity і ініціалізує AppGraph. */
class AutoRotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
