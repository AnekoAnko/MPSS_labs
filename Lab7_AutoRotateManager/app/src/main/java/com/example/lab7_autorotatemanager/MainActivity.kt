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
 * Hosts the screens and forwards lifecycle events to ViewModels as actions.
 * Dependencies are injected through AppViewModelFactory.
 */
class MainActivity : ComponentActivity() {

    private val factory by lazy { AppViewModelFactory((application as AutoRotateApp).container) }

    private val monitorVm: MonitorViewModel by viewModels { factory }     // sensor and orientation
    private val bleVm: BleViewModel by viewModels { factory }             // BLE hub (mock)
    private val cloudVm: CloudViewModel by viewModels { factory }         // cloud sync
    private val controlVm: ControlViewModel by viewModels { factory }     // actuators
    private val analyticsVm: AnalyticsViewModel by viewModels { factory } // Room + chart
    private val aiVm: AiViewModel by viewModels { factory }               // on-device ML
    private val dashboardVm: DashboardViewModel by viewModels { factory } // main dashboard
    private val scenarioVm: ScenarioViewModel by viewModels { factory }   // smart scenarios

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Create background ViewModels eagerly so they subscribe even before their tab is opened
        bleVm        // sends ALARM:ON to the hub even if the BLE tab was never opened
        cloudVm
        controlVm
        analyticsVm
        aiVm
        scenarioVm   // rules work from any tab

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }   // selected tab is UI state
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
                                    AiScreen(aiVm)        // AI Insights
                                    CloudScreen(cloudVm)
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
        val TABS = listOf("Dashboard", "Сенсор", "BLE-хаб", "Керування", "Аналітика", "Сценарії")
    }
}
