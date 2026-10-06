package com.example.lab6_autorotatemanager

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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lab6_autorotatemanager.ai.AiScreen
import com.example.lab6_autorotatemanager.ai.AiViewModel
import com.example.lab6_autorotatemanager.analytics.AnalyticsScreen
import com.example.lab6_autorotatemanager.analytics.AnalyticsViewModel
import com.example.lab6_autorotatemanager.ble.BleScreen
import com.example.lab6_autorotatemanager.ble.BleViewModel
import com.example.lab6_autorotatemanager.cloud.CloudScreen
import com.example.lab6_autorotatemanager.cloud.CloudViewModel
import com.example.lab6_autorotatemanager.control.ControlScreen
import com.example.lab6_autorotatemanager.control.ControlViewModel
import com.example.lab6_autorotatemanager.monitor.MonitorAction
import com.example.lab6_autorotatemanager.monitor.MonitorScreen
import com.example.lab6_autorotatemanager.monitor.MonitorViewModel

/**
 * Показує екрани і перетворює події життєвого циклу на Action — логіки тут немає.
 * Кожна ViewModel переживає поворот екрана.
 */
class MainActivity : ComponentActivity() {

    private val monitorVm: MonitorViewModel by viewModels()   // ЛР №1: сенсор і орієнтація
    private val bleVm: BleViewModel by viewModels()           // ЛР №2: BLE-хаб
    private val cloudVm: CloudViewModel by viewModels()       // ЛР №3: хмарна синхронізація
    private val controlVm: ControlViewModel by viewModels()   // ЛР №4: виконавчі пристрої
    private val analyticsVm: AnalyticsViewModel by viewModels() // ЛР №5: Room + графік
    private val aiVm: AiViewModel by viewModels()               // ЛР №6: TensorFlow Lite

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Створюємо ViewModel одразу: вони підписуються на сенсор і хмару, навіть якщо їхню вкладку ще не відкрито
        cloudVm
        controlVm
        analyticsVm
        aiVm

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }   // навігація — стан самого UI
                    Column(Modifier.safeDrawingPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            TABS.forEachIndexed { i, title ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                            }
                        }
                        when (tab) {
                            0 -> MonitorScreen(monitorVm) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AiScreen(aiVm)        // ЛР №6: «AI Insights»
                                    CloudScreen(cloudVm)  // ЛР №3
                                }
                            }
                            1 -> BleScreen(bleVm)
                            2 -> ControlScreen(controlVm)
                            else -> AnalyticsScreen(analyticsVm)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        monitorVm.onAction(MonitorAction.Start)   // екран видно → слухаємо сенсор
    }

    override fun onPause() {
        monitorVm.onAction(MonitorAction.Stop)    // екран сховано → відписуємось, економимо батарею
        super.onPause()
    }

    private companion object {
        val TABS = listOf("Сенсор", "BLE-хаб", "Керування", "Аналітика")
    }
}
