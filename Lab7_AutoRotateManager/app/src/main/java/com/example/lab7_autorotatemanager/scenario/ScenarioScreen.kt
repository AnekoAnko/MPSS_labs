package com.example.lab7_autorotatemanager.scenario

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lab7_autorotatemanager.helpers.NoiseLevel
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.Comparison
import com.example.lab7_autorotatemanager.repository.Metric

private val ActiveRed = Color(0xFFC62828)

/** Scenarios tab: renders state, sends actions, shows events. */
@Composable
fun ScenarioScreen(vm: ScenarioViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ScenarioEvent.ShowMessage -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    ScenarioContent(state, vm::onAction)
}

@Composable
fun ScenarioContent(s: ScenarioState, onAction: (ScenarioAction) -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Smart-сценарії", fontWeight = FontWeight.Bold)
                        Text("IF умова THEN дія · спрацювань: ${s.firedCount}", style = small)
                    }
                    Switch(checked = s.enabled, onCheckedChange = { onAction(ScenarioAction.SetEnabled(it)) })
                }
                Text(
                    "X=${"%.1f".format(s.x)}  Y=${"%.1f".format(s.y)}  Z=${"%.1f".format(s.z)}  " +
                        "|a|−g=${"%.1f".format(s.dynamic)}  нахил=${"%.0f".format(s.tilt)}°",
                    style = small, fontFamily = FontFamily.Monospace
                )
                Text("Шумопоглинання (медіанний фільтр):", style = small)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NoiseLevel.entries.forEach { level ->
                        val selected = level == s.noise
                        if (selected) Button(onClick = {}) { Text(level.window.toString()) }
                        else OutlinedButton(onClick = { onAction(ScenarioAction.SetNoise(level)) }) { Text(level.window.toString()) }
                    }
                }
                Text("Зараз: ${s.noise.label}. 1 — без фільтра (для тесту «Хибне спрацювання»).", style = small)
            }
        }

        Text("Правила", fontWeight = FontWeight.Bold)
        if (s.rules.isEmpty()) Text("Правил ще немає", style = small)
        s.rules.forEach { item ->
            val r = item.rule
            Card(
                Modifier.fillMaxWidth(),
                colors = if (item.active) CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)) else CardDefaults.cardColors()
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.describe(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "витримка ${r.holdMs} мс" + if (item.active) " · АКТИВНЕ" else "",
                            style = small, color = if (item.active) ActiveRed else Color.Unspecified
                        )
                    }
                    Switch(checked = r.enabled, onCheckedChange = { onAction(ScenarioAction.ToggleRule(r.id, it)) })
                    TextButton(onClick = { onAction(ScenarioAction.DeleteRule(r.id)) }) { Text("✕") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Нове правило", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Якщо ")
                    Picker(s.draft.metric.label, Metric.entries.map { it.label + ", " + it.unit }) {
                        onAction(ScenarioAction.DraftMetric(Metric.entries[it]))
                    }
                    Picker(s.draft.comparison.symbol, Comparison.entries.map { it.symbol }) {
                        onAction(ScenarioAction.DraftComparison(Comparison.entries[it]))
                    }
                    OutlinedTextField(
                        value = s.draft.threshold,
                        onValueChange = { onAction(ScenarioAction.DraftThreshold(it)) },
                        singleLine = true,
                        isError = s.draftError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(96.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("тоді ")
                    Picker(s.draft.action.label, ActuatorType.entries.map { it.label }) {
                        onAction(ScenarioAction.DraftAction(ActuatorType.entries[it]))
                    }
                }
                s.draftError?.let { Text(it, color = ActiveRed, style = small) }
                Text("Витримка: ${s.draft.holdMs} мс", style = small)
                Slider(
                    value = s.draft.holdMs.toFloat(),
                    onValueChange = { onAction(ScenarioAction.DraftHold((it / 100f).toLong() * 100)) },
                    valueRange = 0f..ScenarioViewModel.MAX_HOLD_MS.toFloat()
                )
                Button(onClick = { onAction(ScenarioAction.AddRule) }) { Text("Додати правило") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Журнал", fontWeight = FontWeight.Bold)
                if (s.log.isEmpty()) Text("—", style = small)
                s.log.asReversed().forEach { Text(it, style = small, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

/** Button with a dropdown menu. */
@Composable
private fun Picker(current: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$current ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}
