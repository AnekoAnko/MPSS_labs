package com.example.lab7_autorotatemanager.monitor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.example.lab7_autorotatemanager.helpers.SensorReader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.min

/**
 * Вкладка «Сенсор»: малює State і надсилає Action.
 * [header] — картки інших модулів на цій вкладці (хмарна синхронізація тощо).
 */
@Composable
fun MonitorScreen(vm: MonitorViewModel, header: @Composable () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Слухаємо разові Event
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is MonitorEvent.RotateScreen -> context.findActivity()?.requestedOrientation = event.requestedOrientation
            }
        }
    }

    MonitorContent(state = state, onAction = vm::onAction, header = header)
}

// Чиста функція: State на вході, Action на виході.
@Composable
fun MonitorContent(
    state: MonitorState,
    onAction: (MonitorAction) -> Unit,
    header: @Composable () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Auto-Rotate Manager", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        header()

        if (!state.sensorAvailable) {
            Text("Акселерометр недоступний на цьому пристрої", color = MaterialTheme.colorScheme.error)
        }

        // Значення X, Y, Z
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Акселерометр, м/с²", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                listOf("X" to state.x, "Y" to state.y, "Z" to state.z).forEach { (name, value) ->
                    Text("$name = ${"%+.2f".format(value)}", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                }
            }
        }

        Text("Орієнтація: ${state.orientation.label}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        OrientationIndicator(x = state.x, y = state.y, angle = state.screenAngle)

        // Індикатор кута нахилу (0..90°)
        Column(Modifier.fillMaxWidth()) {
            Text("Нахил від вертикалі: ${"%.0f".format(abs(state.screenAngle))}°")
            LinearProgressIndicator(
                progress = { min(abs(state.screenAngle), 90f) / 90f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Керувати поворотом екрана")
            Switch(checked = state.autoRotate, onCheckedChange = { onAction(MonitorAction.SetAutoRotate(it)) })
        }
    }
}

/**
 * Canvas-індикатор: стрілка показує, де «верх» у реальному світі,
 * а точка — напрямок гравітації в площині екрана.
 */
@Composable
fun OrientationIndicator(x: Float, y: Float, angle: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline

    Canvas(modifier = Modifier.size(220.dp)) {
        val r = size.minDimension / 2 * 0.9f
        drawCircle(color = outline, radius = r, style = Stroke(width = 4f))

        rotate(degrees = angle) {
            val tip = Offset(center.x, center.y - r * 0.8f)
            drawLine(primary, center, tip, strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(primary, tip, Offset(tip.x - 20f, tip.y + 28f), strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(primary, tip, Offset(tip.x + 20f, tip.y + 28f), strokeWidth = 10f, cap = StrokeCap.Round)
        }

        val g = SensorReader.GRAVITY
        val dot = Offset(
            center.x + (-x / g).coerceIn(-1f, 1f) * r,
            center.y + (y / g).coerceIn(-1f, 1f) * r
        )
        drawCircle(color = secondary, radius = 16f, center = dot)
    }
}

/** LocalContext у Compose — це Activity (можливо, загорнута в ContextWrapper). */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
