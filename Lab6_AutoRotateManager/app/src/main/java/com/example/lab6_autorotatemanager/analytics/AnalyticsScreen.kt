package com.example.lab6_autorotatemanager.analytics

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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

/** Analytics tab: chart from Room, hourly stats, trigger history. */
@Composable
fun AnalyticsScreen(vm: AnalyticsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is AnalyticsEvent.ShowMessage -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AnalyticsContent(state = state, onAction = vm::onAction)
}

// Stateless content: state in, actions out.
@Composable
fun AnalyticsContent(state: AnalyticsState, onAction: (AnalyticsAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Локальна БД · Room", fontWeight = FontWeight.Bold)
                        Text(
                            if (state.fastMode) "ШІ: «Біг» → прискорений запис кожні ${RecordPolicy.FAST_INTERVAL_MS} мс"
                            else "Запис: Δ ≥ ${RecordPolicy.DEFAULT_MIN_DELTA.toInt()}° або кожні " +
                                "${RecordPolicy.DEFAULT_HEARTBEAT_MS / 1000} с",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.fastMode) Color(0xFFE65100) else Color.Unspecified
                        )
                    }
                    Switch(checked = state.recording, onCheckedChange = { onAction(AnalyticsAction.SetRecording(it)) })
                }
                Text("Записів у таблиці sensor_records: ${state.recordCount}")
                Text("Записано за сесію: ${state.writes}")
                state.lastWrite?.let {
                    Text("Останній: $it", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                state.error?.let { Text("Помилка БД: $it", color = MaterialTheme.colorScheme.error) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Нахил від вертикалі, ° (останні ${AnalyticsViewModel.CHART_POINTS} точок)", fontWeight = FontWeight.Bold)
                SensorLineChart(
                    points = state.points,
                    threshold = state.threshold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(top = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendDot(MaterialTheme.colorScheme.primary)
                    Text("значення", style = MaterialTheme.typography.bodySmall)
                    LegendDot(Color(0xFFC62828))
                    Text("спрацювання (actionTriggered)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("За останню годину (SQL: MIN/MAX/AVG)", fontWeight = FontWeight.Bold)
                val s = state.hourStats
                if (s == null || s.count == 0) {
                    Text("Даних ще немає")
                } else {
                    Text(
                        "Записів: ${s.count} · спрацювань: ${s.triggers ?: 0}\n" +
                            "min ${fmt(s.minValue)}° · max ${fmt(s.maxValue)}° · avg ${fmt(s.avgValue)}°",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Історія спрацювань системи безпеки", fontWeight = FontWeight.Bold)
                if (state.history.isEmpty()) {
                    Text("Спрацювань ще не було (нахиліть пристрій понад ${state.threshold.toInt()}°)")
                } else {
                    state.history.forEach { r ->
                        Text(
                            "${r.time}  ${fmt(r.value)}°  ${r.orientation}",
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = { onAction(AnalyticsAction.ClearHistory) }, modifier = Modifier.fillMaxWidth()) {
            Text("Очистити історію")
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private fun fmt(value: Float?): String = if (value == null) "—" else "%.1f".format(value)
