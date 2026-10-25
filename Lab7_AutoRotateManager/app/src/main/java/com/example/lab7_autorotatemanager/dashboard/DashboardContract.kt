package com.example.lab7_autorotatemanager.dashboard

import com.example.lab7_autorotatemanager.repository.ActuatorSnapshot
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.AiSnapshot
import com.example.lab7_autorotatemanager.repository.BleLink
import com.example.lab7_autorotatemanager.repository.CloudLink

/** Один рядок «Останні 5 записів БД». */
data class RecordItem(
    val time: String,
    val tilt: Float,
    val orientation: String,
    val activity: String,
    val triggered: Boolean,
    val synced: Boolean
)

// State — головний екран Dashboard (ЛР №7): зведення всіх модулів ЛР №1–6.
data class DashboardState(
    /** Віджет поточного стану з ML-моделі (ЛР №6). */
    val ai: AiSnapshot = AiSnapshot(),
    /** Критичний стан: тривога ЛР №4 (нахил) або «Аномалія» ЛР №6. */
    val critical: Boolean = false,
    val orientation: String = "PORTRAIT",
    /** Останні 5 записів Room (ЛР №5). */
    val records: List<RecordItem> = emptyList(),
    val recordCount: Int = 0,
    /** Пам'ять пристрою заповнена: записи йдуть лише в хмару. */
    val storageFull: Boolean = false,
    /** Статус хмари (ЛР №3) і Bluetooth-мока (ЛР №2). */
    val cloud: CloudLink = CloudLink(),
    val ble: BleLink = BleLink(),
    /** Стан актуаторів (ЛР №4) для кнопок швидкого керування. */
    val actuators: ActuatorSnapshot = ActuatorSnapshot()
)

// Action — кнопки швидкого керування.
sealed interface DashboardAction {
    data class Toggle(val type: ActuatorType, val on: Boolean) : DashboardAction
    data object AllOff : DashboardAction
}

// Event — разові повідомлення.
sealed interface DashboardEvent {
    data class ShowMessage(val text: String) : DashboardEvent
}
