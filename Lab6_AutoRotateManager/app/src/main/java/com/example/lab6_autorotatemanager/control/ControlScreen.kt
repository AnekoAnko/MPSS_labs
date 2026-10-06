package com.example.lab6_autorotatemanager.control

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import com.example.lab6_autorotatemanager.helpers.RemoteConnection

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFF9A825)
private val Grey = Color(0xFF9E9E9E)

/** Вкладка «Керування»: малює State, надсилає Action, показує Event. */
@Composable
fun ControlScreen(vm: ControlViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is ControlEvent.ShowMessage -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    ControlContent(s = state, onAction = vm::onAction)
}

/** DecisionEngine, віртуальна лампа, Manual Control, Cloud-to-Device, журнал. */
@Composable
fun ControlContent(s: ControlState, onAction: (ControlAction) -> Unit) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Банер тривоги
        if (s.alarmActive) {
            Card(colors = CardDefaults.cardColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "ТРИВОГА: нахил ${"%.0f".format(s.tilt)}° > ${"%.0f".format(s.threshold)}°",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        // ЛР №6: тривога за прогнозом ML-моделі
        if (s.aiAlarm) {
            Card(colors = CardDefaults.cardColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "ТРИВОГА (ШІ): розпізнано аномальну вібрацію",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        // ---------------------------------------------------------------- DecisionEngine
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DecisionEngine (авто)", fontWeight = FontWeight.Bold)
                        Text(
                            "IF нахил > ${"%.0f".format(s.threshold)}° THEN сирена + прожектор",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(checked = s.autoEnabled, onCheckedChange = { onAction(ControlAction.SetAutoEnabled(it)) })
                }
                Text("Нахил від вертикалі: ${"%.1f".format(s.tilt)}°", fontSize = 18.sp)
                LinearProgressIndicator(
                    progress = { (s.tilt / 180f).coerceIn(0f, 1f) },
                    color = if (s.tilt > s.threshold) Red else Green,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Поріг (Threshold): ${"%.0f".format(s.threshold)}°  ·  гістерезис 10°  ·  витримка 300 мс",
                    style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = s.threshold,
                    onValueChange = { onAction(ControlAction.SetThreshold(it)) },
                    valueRange = DecisionEngine.MIN_THRESHOLD..DecisionEngine.MAX_THRESHOLD
                )
            }
        }

        // ---------------------------------------------------------------- Віртуальна лампа
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val lampColor by animateColorAsState(if (s.lamp.on) Color(0xFFFFD54F) else Color(0xFF424242), label = "lamp")
                Box(
                    Modifier
                        .size(72.dp)
                        .background(lampColor, CircleShape)
                        .border(3.dp, if (s.lamp.on) Amber else Grey, CircleShape)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Віртуальна лампа (реле)", fontWeight = FontWeight.Bold)
                    Text(if (s.lamp.on) "УВІМКНЕНО" else "ВИМКНЕНО", color = if (s.lamp.on) Green else Grey,
                        fontWeight = FontWeight.SemiBold)
                    s.lamp.changedAt?.let { Text("$it · ${s.lamp.source.label}", style = MaterialTheme.typography.bodySmall) }
                    Text("Хмара: control/led_status", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ---------------------------------------------------------------- Manual Control
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Manual Control", fontWeight = FontWeight.Bold)
                ActuatorRow(
                    title = "Прожектор (ліхтарик)",
                    status = s.flashlight,
                    note = if (s.hasFlash) "CameraManager.setTorchMode" else "немає FEATURE_CAMERA_FLASH → імітація",
                    onToggle = { onAction(ControlAction.SetFlashlight(it)) }
                )
                ActuatorRow(
                    title = "Сирена (вібрація + звук)",
                    status = s.siren,
                    note = if (s.hasVibrator) "VibratorManager + ToneGenerator" else "немає вібромотора → лише звук",
                    onToggle = { onAction(ControlAction.SetSiren(it)) }
                )
                ActuatorRow(
                    title = "Віртуальна лампа",
                    status = s.lamp,
                    note = "стан лише в UI та у Firebase",
                    onToggle = { onAction(ControlAction.SetLamp(it)) }
                )
                OutlinedButton(onClick = { onAction(ControlAction.AllOff) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Вимкнути все")
                }
            }
        }

        // ---------------------------------------------------------------- Cloud-to-Device
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val color = when (s.remoteConnection) {
                    RemoteConnection.CONNECTED -> Green
                    RemoteConnection.CONNECTING -> Amber
                    RemoteConnection.ERROR -> Red
                    RemoteConnection.DISCONNECTED -> Grey
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("Дистанційне керування: ${s.remoteConnection.label}", fontWeight = FontWeight.Bold)
                }
                val small = MaterialTheme.typography.bodySmall
                Text("Режим: ${s.remoteMode.label}", style = small)
                s.remoteDetails?.let { Text(it, style = small) }
                s.lastCommandRaw?.let {
                    Text("Остання команда (${s.lastCommandTime}):", style = small)
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
                s.feedbackStatus?.let { Text("Зворотний зв'язок: $it", style = small) }
            }
        }

        // ---------------------------------------------------------------- Журнал
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Журнал подій (Logcat: Control)", fontWeight = FontWeight.Bold)
                s.log.forEach {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun ActuatorRow(title: String, status: ActuatorStatus, note: String, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (status.on) Amber else Grey, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall)
            status.changedAt?.let {
                Text("$it · ${status.source.label}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
        Switch(checked = status.on, onCheckedChange = onToggle)
    }
}
