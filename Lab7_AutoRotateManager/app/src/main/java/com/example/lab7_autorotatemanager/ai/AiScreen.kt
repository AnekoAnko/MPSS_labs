package com.example.lab7_autorotatemanager.ai

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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

/** Color used for the class label and probability bar (also on the dashboard). */
fun ActivityClass.color(): Color = when (this) {
    ActivityClass.STILL -> Color(0xFF546E7A)
    ActivityClass.WALKING -> Color(0xFF2E7D32)
    ActivityClass.RUNNING -> Color(0xFFE65100)
    ActivityClass.ANOMALY -> Color(0xFFC62828)
}

/** AI Insights card: renders state, sends actions, shows events. */
@Composable
fun AiScreen(vm: AiViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is AiEvent.ShowMessage -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    AiContent(state = state, onAction = vm::onAction)
}

/** Live prediction: class, probabilities, inference time. */
@Composable
fun AiContent(state: AiState, onAction: (AiAction) -> Unit) {
    val prediction = state.prediction
    val small = MaterialTheme.typography.bodySmall

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI Insights", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("On-device · TensorFlow Lite · без мережі", style = small)
                }
                Switch(checked = state.enabled, onCheckedChange = { onAction(AiAction.SetEnabled(it)) })
            }

            when {
                state.modelError != null ->
                    Text("Помилка моделі: ${state.modelError}", color = MaterialTheme.colorScheme.error)
                !state.enabled -> Text("Класифікацію вимкнено", style = small)
                state.modelInfo == null -> Text("Завантаження моделі…", style = small)
                prediction == null -> Text(
                    "Накопичення вікна: ${state.bufferFill}/${state.bufferSize} вимірів…",
                    style = small
                )
                else -> {
                    Text(
                        "Current Activity: ${prediction.format()}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = prediction.best.color()
                    )
                    Text("Прогноз: ${prediction.best.label} · підтверджено: ${state.stable.label}", style = small)
                }
            }

            // Probability bar for every class
            ActivityClass.entries.forEach { cls ->
                val p = prediction?.probabilities?.getOrNull(cls.ordinal) ?: 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cls.label, style = small, modifier = Modifier.width(78.dp))
                    LinearProgressIndicator(
                        progress = { p },
                        color = cls.color(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${"%3.0f".format(p * 100)}%",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.width(44.dp).padding(start = 6.dp)
                    )
                }
            }

            // Side effects of the confirmed class
            when (state.stable) {
                ActivityClass.RUNNING -> Text("→ ЛР №5: запис у БД прискорено", color = ActivityClass.RUNNING.color(), style = small)
                ActivityClass.ANOMALY -> Text("→ ЛР №4: сирена + прожектор", color = ActivityClass.ANOMALY.color(), style = small)
                else -> Unit
            }

            val mono = Modifier.padding(top = 2.dp)
            Text(
                "Вікно ${state.bufferFill}/${state.bufferSize} · inference #${state.inferenceCount} · " +
                    "${"%.2f".format(state.avgInferenceMs)} мс (сер.) · пропущено ${state.skippedWindows}",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, modifier = mono
            )
            state.workerThread?.let {
                Text("Потік inference: $it", fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
            }
            state.modelInfo?.let {
                Text(
                    "${it.fileName} · ${"%.1f".format(it.sizeBytes / 1024f)} КБ · ${it.describe()}",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp
                )
            }
            state.history.forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
