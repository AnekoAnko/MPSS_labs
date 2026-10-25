package com.example.lab7_autorotatemanager.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.lab7_autorotatemanager.ai.AiViewModel
import com.example.lab7_autorotatemanager.analytics.AnalyticsViewModel
import com.example.lab7_autorotatemanager.ble.BleViewModel
import com.example.lab7_autorotatemanager.cloud.CloudViewModel
import com.example.lab7_autorotatemanager.control.ControlViewModel
import com.example.lab7_autorotatemanager.dashboard.DashboardViewModel
import com.example.lab7_autorotatemanager.monitor.MonitorViewModel
import com.example.lab7_autorotatemanager.scenario.ScenarioViewModel

/**
 * ЛР №7: фабрика ViewModel для Manual DI. Android створює ViewModel сам (щоб вона пережила
 * поворот екрана), тому передати аргументи в конструктор можна лише через Factory.
 * MainActivity: `private val vm: XxxViewModel by viewModels { factory }`.
 */
class AppViewModelFactory(private val c: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        MonitorViewModel::class.java -> MonitorViewModel(c.sensorReader(), c.sensorHub, c.systemState)
        BleViewModel::class.java -> BleViewModel(c.bluetoothEnvironment(), c.systemState)
        CloudViewModel::class.java -> CloudViewModel(c.sensorRepository, c.connectivityMonitor(), c.sensorHub, c.systemState, c.deviceId)
        ControlViewModel::class.java -> ControlViewModel(
            c.actuator(), c.remoteCommandSource(), c.sensorRepository, c.sensorHub, c.systemState, c.deviceId
        )
        AnalyticsViewModel::class.java -> AnalyticsViewModel(c.sensorRepository, c.sensorHub, c.systemState)
        AiViewModel::class.java -> AiViewModel({ c.activityClassifier() }, c.sensorHub, c.systemState)
        DashboardViewModel::class.java -> DashboardViewModel(c.systemState, c.sensorRepository)
        ScenarioViewModel::class.java -> ScenarioViewModel(c.sensorHub, c.scenarioRepository, c.systemState)
        else -> throw IllegalArgumentException("Невідома ViewModel: ${modelClass.name}")
    } as T
}
