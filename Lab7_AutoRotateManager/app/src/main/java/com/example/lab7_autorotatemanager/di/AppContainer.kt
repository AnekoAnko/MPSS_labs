package com.example.lab7_autorotatemanager.di

import android.app.Application
import com.example.lab7_autorotatemanager.BuildConfig
import com.example.lab7_autorotatemanager.helpers.ActivityClassifier
import com.example.lab7_autorotatemanager.helpers.ConnectivityMonitor
import com.example.lab7_autorotatemanager.helpers.IActivityClassifier
import com.example.lab7_autorotatemanager.helpers.IActuator
import com.example.lab7_autorotatemanager.helpers.RemoteCommandSource
import com.example.lab7_autorotatemanager.helpers.RemoteSourceFactory
import com.example.lab7_autorotatemanager.helpers.SensorHub
import com.example.lab7_autorotatemanager.helpers.SensorReader
import com.example.lab7_autorotatemanager.helpers.SmartActuator
import com.example.lab7_autorotatemanager.helpers.ble.BluetoothEnvironment
import com.example.lab7_autorotatemanager.helpers.deviceIdOf
import com.example.lab7_autorotatemanager.repository.AppDatabase
import com.example.lab7_autorotatemanager.repository.CloudModule
import com.example.lab7_autorotatemanager.repository.ScenarioRepository
import com.example.lab7_autorotatemanager.repository.SensorRepository
import com.example.lab7_autorotatemanager.repository.SystemStateRepository

/**
 * ЛР №7: Manual Dependency Injection.
 *
 * Контейнер — єдине місце, де створюються залежності. ViewModel отримують усе через конструктор
 * і не знають, звідки воно взялося. У тестах замість DefaultAppContainer можна передати
 * контейнер із фейками (FakeCloudApi, in-memory Room, фейковий актуатор).
 *
 *  • singletons (одні на весь процес): SensorHub, SystemStateRepository, AppDatabase, репозиторії;
 *  • factories (новий об'єкт для кожної ViewModel): SensorReader, SmartActuator, ConnectivityMonitor…
 *    — їх звільняє ViewModel в onCleared(), тому вони не можуть «пережити» свого власника.
 */
interface AppContainer {
    val deviceId: String
    val sensorHub: SensorHub
    val systemState: SystemStateRepository
    val sensorRepository: SensorRepository
    val scenarioRepository: ScenarioRepository

    fun sensorReader(): SensorReader
    fun actuator(): IActuator
    fun remoteCommandSource(): RemoteCommandSource?
    fun connectivityMonitor(): ConnectivityMonitor
    fun bluetoothEnvironment(): BluetoothEnvironment
    fun activityClassifier(): IActivityClassifier
}

/** Реальні реалізації. Отримує лише Application (а не Activity) — витоку Activity бути не може. */
class DefaultAppContainer(private val app: Application) : AppContainer {

    override val deviceId: String by lazy { deviceIdOf(app) }

    override val sensorHub: SensorHub by lazy { SensorHub() }

    override val systemState: SystemStateRepository by lazy { SystemStateRepository() }

    private val database: AppDatabase by lazy { AppDatabase.getInstance(app) }

    override val sensorRepository: SensorRepository by lazy {
        SensorRepository(
            api = CloudModule.createApi(BuildConfig.FIREBASE_DB_URL),
            authToken = BuildConfig.FIREBASE_AUTH.ifBlank { null },
            deviceId = deviceId,
            dao = database.sensorDao()
        )
    }

    override val scenarioRepository: ScenarioRepository by lazy { ScenarioRepository(database.scenarioDao()) }

    override fun sensorReader() = SensorReader(app)
    override fun actuator(): IActuator = SmartActuator(app)
    override fun remoteCommandSource(): RemoteCommandSource? = RemoteSourceFactory.create(app)
    override fun connectivityMonitor() = ConnectivityMonitor(app)
    override fun bluetoothEnvironment() = BluetoothEnvironment(app)
    override fun activityClassifier(): IActivityClassifier = ActivityClassifier(app)
}
