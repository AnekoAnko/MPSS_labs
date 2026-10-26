package com.example.lab5_autorotatemanager

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
import com.example.lab5_autorotatemanager.analytics.AnalyticsScreen
import com.example.lab5_autorotatemanager.analytics.AnalyticsViewModel
import com.example.lab5_autorotatemanager.ble.BleScreen
import com.example.lab5_autorotatemanager.ble.BleViewModel
import com.example.lab5_autorotatemanager.cloud.CloudAction
import com.example.lab5_autorotatemanager.cloud.CloudScreen
import com.example.lab5_autorotatemanager.cloud.CloudViewModel
import com.example.lab5_autorotatemanager.control.ControlAction
import com.example.lab5_autorotatemanager.control.ControlScreen
import com.example.lab5_autorotatemanager.control.ControlViewModel
import com.example.lab5_autorotatemanager.monitor.MonitorAction
import com.example.lab5_autorotatemanager.monitor.MonitorScreen
import com.example.lab5_autorotatemanager.monitor.MonitorViewModel

/** Hosts the screens and forwards lifecycle events to ViewModels as actions. */
class MainActivity : ComponentActivity() {

    private val monitorVm: MonitorViewModel by viewModels()   // sensor and orientation
    private val bleVm: BleViewModel by viewModels()           // BLE hub
    private val cloudVm: CloudViewModel by viewModels()       // cloud sync
    private val controlVm: ControlViewModel by viewModels()   // actuators
    private val analyticsVm: AnalyticsViewModel by viewModels() // Room + chart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Create background ViewModels eagerly so they subscribe even before their tab is opened
        cloudVm
        controlVm
        analyticsVm

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }   // selected tab is UI state
                    Column(Modifier.safeDrawingPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            TABS.forEachIndexed { i, title ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                            }
                        }
                        when (tab) {
                            0 -> MonitorScreen(monitorVm) { CloudScreen(cloudVm) }
                            1 -> BleScreen(bleVm)
                            2 -> ControlScreen(controlVm)
                            else -> AnalyticsScreen(analyticsVm)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cloudVm.onAction(CloudAction.Start)       // watch the network (pending queue)
        controlVm.onAction(ControlAction.Start)   // listen for Firebase commands
    }

    override fun onResume() {
        super.onResume()
        monitorVm.onAction(MonitorAction.Start)   // screen visible: start the sensor
    }

    override fun onPause() {
        monitorVm.onAction(MonitorAction.Stop)    // screen hidden: stop the sensor to save battery
        super.onPause()
    }

    override fun onStop() {
        // Skip on rotation: the Activity is recreated, network subscriptions stay alive
        if (!isChangingConfigurations) {
            cloudVm.onAction(CloudAction.Stop)
            controlVm.onAction(ControlAction.Stop)
        }
        super.onStop()
    }

    private companion object {
        val TABS = listOf("Сенсор", "BLE-хаб", "Керування", "Аналітика")
    }
}
