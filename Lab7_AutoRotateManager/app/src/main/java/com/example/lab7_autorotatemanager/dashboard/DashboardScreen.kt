package com.example.lab7_autorotatemanager.dashboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lab7_autorotatemanager.ai.ActivityClass
import com.example.lab7_autorotatemanager.repository.ActuatorType

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFF9A825)
private val Grey = Color(0xFF9E9E9E)

private fun ActivityClass.color(): Color = when (this) {
    ActivityClass.STILL -> Color(0xFF546E7A)
    ActivityClass.WALKING -> Green
    ActivityClass.RUNNING -> Color(0xFFE65100)
    ActivityClass.ANOMALY -> Red
}

/** Dashboard: renders state, sends actions, shows events. */
@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is DashboardEvent.ShowMessage -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    DashboardContent(state, vm::onAction)
}

@Composable
fun DashboardContent(s: DashboardState, onAction: (DashboardAction) -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (s.critical) {
            Card(colors = CardDefaults.cardColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "КРИТИЧНИЙ СТАН: актуатори ввімкнено, команда ALARM:ON → BLE-хаб",
                    color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp)
                )
            }
        }

        if (s.storageFull) {
            Card(colors = CardDefaults.cardColors(containerColor = Amber), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Пам'ять заповнена: старі синхронізовані записи видалено, нові йдуть лише в хмару",
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp)
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Поточний стан (TFLite)", fontWeight = FontWeight.Bold)
                val current = s.ai.current
                if (!s.ai.enabled) {
                    Text("Класифікацію вимкнено (вкладка «Сенсор» → AI Insights)", color = Grey)
                } else if (current == null) {
                    Text("Накопичення вікна даних…", color = Grey)
                } else {
                    Text(
                        "${current.label} (${"%.0f".format(s.ai.confidence * 100)}%)",
                        color = current.color(), fontWeight = FontWeight.Bold, fontSize = 26.sp
                    )
                }
                Text("Підтверджено: ${s.ai.stable.label} · орієнтація: ${s.orientation}", style = small)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(
                title = "Хмара (Firebase)",
                ok = s.cloud.configured && s.cloud.online,
                lines = listOf(
                    if (!s.cloud.configured) "не налаштовано" else if (s.cloud.online) "онлайн" else "офлайн",
                    s.cloud.status,
                    "у черзі: ${s.cloud.pending}",
                    "синхр.: ${s.cloud.lastSync ?: "—"}"
                ),
                warn = s.cloud.pending > 0,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "BLE-хаб (Mock)",
                ok = s.ble.linkUp,
                lines = listOf(
                    s.ble.state,
                    s.ble.device ?: "пристрій не вибрано",
                    s.ble.lastStatus ?: "—",
                    "TX: ${s.ble.lastCommand ?: "—"}"
                ),
                warn = false,
                modifier = Modifier.weight(1f)
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Швидке керування", fontWeight = FontWeight.Bold)
                QuickSwitch("Прожектор", s.actuators.flashlight) { onAction(DashboardAction.Toggle(ActuatorType.FLASHLIGHT, it)) }
                QuickSwitch("Сирена", s.actuators.siren) { onAction(DashboardAction.Toggle(ActuatorType.SIREN, it)) }
                QuickSwitch("Лампа (реле)", s.actuators.lamp) { onAction(DashboardAction.Toggle(ActuatorType.LAMP, it)) }
                QuickSwitch("Реле BLE-хаба", s.actuators.hubRelay) { onAction(DashboardAction.Toggle(ActuatorType.HUB_RELAY, it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAction(DashboardAction.Toggle(ActuatorType.VIBRATION, true)) }) { Text("Вібро-імпульс") }
                    OutlinedButton(onClick = { onAction(DashboardAction.AllOff) }) { Text("Вимкнути все") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Останні записи Room (усього ${s.recordCount})", fontWeight = FontWeight.Bold)
                if (s.records.isEmpty()) Text("Записів ще немає", style = small)
                s.records.forEach { r ->
                    Text(
                        "${r.time}  ${"%5.1f".format(r.tilt)}°  ${r.activity.take(7).padEnd(7)}  " +
                            (if (r.triggered) "⚠ " else "  ") + if (r.synced) "☁" else "⏳",
                        style = small, fontFamily = FontFamily.Monospace,
                        color = if (r.triggered) Red else Color.Unspecified
                    )
                }
                Text("☁ — у хмарі, ⏳ — чекає синхронізації, ⚠ — подія спрацювання", style = small, color = Grey)
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, ok: Boolean, warn: Boolean, lines: List<String>, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (!ok) Red else if (warn) Amber else Green, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun QuickSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
