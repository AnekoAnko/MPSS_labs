package com.example.lab4_autorotatemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import com.example.lab4_autorotatemanager.ble.BleScreen
import com.example.lab4_autorotatemanager.ble.BleViewModel
import com.example.lab4_autorotatemanager.cloud.CloudScreen
import com.example.lab4_autorotatemanager.cloud.CloudViewModel
import com.example.lab4_autorotatemanager.control.ControlScreen
import com.example.lab4_autorotatemanager.control.ControlViewModel
import com.example.lab4_autorotatemanager.monitor.MonitorAction
import com.example.lab4_autorotatemanager.monitor.MonitorScreen
import com.example.lab4_autorotatemanager.monitor.MonitorViewModel

/**
 * Показує екрани і перетворює події життєвого циклу на Action — логіки тут немає.
 * Кожна ViewModel переживає поворот екрана.
 */
class MainActivity : ComponentActivity() {

    private val monitorVm: MonitorViewModel by viewModels()   // ЛР №1: сенсор і орієнтація
    private val bleVm: BleViewModel by viewModels()           // ЛР №2: BLE-хаб
    private val cloudVm: CloudViewModel by viewModels()       // ЛР №3: хмарна синхронізація
    private val controlVm: ControlViewModel by viewModels()   // ЛР №4: виконавчі пристрої

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Створюємо ViewModel одразу: вони підписуються на сенсор і хмару, навіть якщо їхню вкладку ще не відкрито
        cloudVm
        controlVm

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
                            0 -> MonitorScreen(monitorVm) { CloudScreen(cloudVm) }
                            1 -> BleScreen(bleVm)
                            else -> ControlScreen(controlVm)
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
        val TABS = listOf("Сенсор", "BLE-хаб", "Керування")
    }
}
