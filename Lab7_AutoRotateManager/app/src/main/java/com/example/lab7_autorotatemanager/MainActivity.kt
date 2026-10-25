package com.example.lab7_autorotatemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lab7_autorotatemanager.ai.AiScreen
import com.example.lab7_autorotatemanager.ai.AiViewModel
import com.example.lab7_autorotatemanager.analytics.AnalyticsScreen
import com.example.lab7_autorotatemanager.analytics.AnalyticsViewModel
import com.example.lab7_autorotatemanager.ble.BleScreen
import com.example.lab7_autorotatemanager.ble.BleViewModel
import com.example.lab7_autorotatemanager.cloud.CloudAction
import com.example.lab7_autorotatemanager.cloud.CloudScreen
import com.example.lab7_autorotatemanager.cloud.CloudViewModel
import com.example.lab7_autorotatemanager.control.ControlAction
import com.example.lab7_autorotatemanager.control.ControlScreen
import com.example.lab7_autorotatemanager.control.ControlViewModel
import com.example.lab7_autorotatemanager.dashboard.DashboardScreen
import com.example.lab7_autorotatemanager.dashboard.DashboardViewModel
import com.example.lab7_autorotatemanager.di.AppViewModelFactory
import com.example.lab7_autorotatemanager.monitor.MonitorAction
import com.example.lab7_autorotatemanager.monitor.MonitorScreen
import com.example.lab7_autorotatemanager.monitor.MonitorViewModel
import com.example.lab7_autorotatemanager.scenario.ScenarioScreen
import com.example.lab7_autorotatemanager.scenario.ScenarioViewModel

/**
 * Показує екрани і перетворює події життєвого циклу на Action — логіки тут немає.
 * Кожна ViewModel переживає поворот екрана.
 * ЛР №7: залежності ViewModel отримують через конструктор — їх створює AppViewModelFactory
 * з контейнера AppContainer (Manual DI), а не глобальний об'єкт.
 */
class MainActivity : ComponentActivity() {

    private val factory by lazy { AppViewModelFactory((application as AutoRotateApp).container) }

    private val monitorVm: MonitorViewModel by viewModels { factory }     // ЛР №1: сенсор і орієнтація
    private val bleVm: BleViewModel by viewModels { factory }             // ЛР №2: BLE-хаб (Mock-IoT)
    private val cloudVm: CloudViewModel by viewModels { factory }         // ЛР №3: хмарна синхронізація
    private val controlVm: ControlViewModel by viewModels { factory }     // ЛР №4: виконавчі пристрої
    private val analyticsVm: AnalyticsViewModel by viewModels { factory } // ЛР №5: Room + графік
    private val aiVm: AiViewModel by viewModels { factory }               // ЛР №6: TensorFlow Lite
    private val dashboardVm: DashboardViewModel by viewModels { factory } // ЛР №7: головний екран
    private val scenarioVm: ScenarioViewModel by viewModels { factory }   // ЛР №7: Smart-сценарії

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Створюємо ViewModel одразу: вони підписуються на сенсор і хмару, навіть якщо їхню вкладку ще не відкрито
        bleVm        // ЛР №7: критичний стан → ALARM:ON на хаб, навіть якщо вкладку не відкривали
        cloudVm
        controlVm
        analyticsVm
        aiVm
        scenarioVm   // ЛР №7: правила працюють з будь-якої вкладки

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }   // навігація — стан самого UI
                    Column(Modifier.safeDrawingPadding()) {
                        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                            TABS.forEachIndexed { i, title ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                            }
                        }
                        when (tab) {
                            0 -> DashboardScreen(dashboardVm)
                            1 -> MonitorScreen(monitorVm) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AiScreen(aiVm)        // ЛР №6: «AI Insights»
                                    CloudScreen(cloudVm)  // ЛР №3
                                }
                            }
                            2 -> BleScreen(bleVm)
                            3 -> ControlScreen(controlVm)
                            4 -> AnalyticsScreen(analyticsVm)
                            else -> ScenarioScreen(scenarioVm)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cloudVm.onAction(CloudAction.Start)       // стежимо за мережею (черга Pending)
        controlVm.onAction(ControlAction.Start)   // слухаємо команди з Firebase
    }

    override fun onResume() {
        super.onResume()
        monitorVm.onAction(MonitorAction.Start)   // екран видно → слухаємо сенсор
    }

    override fun onPause() {
        monitorVm.onAction(MonitorAction.Stop)    // екран сховано → відписуємось, економимо батарею
        super.onPause()
    }

    override fun onStop() {
        // Під час повороту екрана Activity перестворюється — мережеві підписки не перепідключаємо
        if (!isChangingConfigurations) {
            cloudVm.onAction(CloudAction.Stop)
            controlVm.onAction(ControlAction.Stop)
        }
        super.onStop()
    }

    private companion object {
        val TABS = listOf("Dashboard", "Сенсор", "BLE-хаб", "Керування", "Аналітика", "Сценарії")
    }
}
