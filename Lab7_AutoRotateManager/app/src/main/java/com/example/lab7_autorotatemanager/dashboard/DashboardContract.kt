package com.example.lab7_autorotatemanager.dashboard

import com.example.lab7_autorotatemanager.repository.ActuatorSnapshot
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.AiSnapshot
import com.example.lab7_autorotatemanager.repository.BleLink
import com.example.lab7_autorotatemanager.repository.CloudLink

/** One row of the "last 5 records" list. */
data class RecordItem(
    val time: String,
    val tilt: Float,
    val orientation: String,
    val activity: String,
    val triggered: Boolean,
    val synced: Boolean
)

// State: the dashboard, a summary of all modules.
data class DashboardState(
    /** Current ML state widget. */
    val ai: AiSnapshot = AiSnapshot(),
    /** Critical state: tilt alarm or anomaly. */
    val critical: Boolean = false,
    val orientation: String = "PORTRAIT",
    /** Last 5 Room records. */
    val records: List<RecordItem> = emptyList(),
    val recordCount: Int = 0,
    /** Storage is full: records go to the cloud only. */
    val storageFull: Boolean = false,
    /** Cloud and BLE status. */
    val cloud: CloudLink = CloudLink(),
    val ble: BleLink = BleLink(),
    /** Actuator state for the quick controls. */
    val actuators: ActuatorSnapshot = ActuatorSnapshot()
)

// Action: quick controls.
sealed interface DashboardAction {
    data class Toggle(val type: ActuatorType, val on: Boolean) : DashboardAction
    data object AllOff : DashboardAction
}

// Event: one-off messages.
sealed interface DashboardEvent {
    data class ShowMessage(val text: String) : DashboardEvent
}
