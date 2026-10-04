package com.example.lab1_autorotatemanager// ← залиш тут той package, який згенерував твій проєкт

import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/**
 * Можливі стани орієнтації пристрою.
 * screenOrientation — значення, яке передаємо в requestedOrientation
 * (null для FLAT: коли телефон лежить, орієнтацію не змінюємо).
 */
enum class DeviceOrientation(val label: String, val screenOrientation: Int?) {
    PORTRAIT("Портрет", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE_LEFT("Альбом (ліворуч)", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    LANDSCAPE_RIGHT("Альбом (праворуч)", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    UPSIDE_DOWN("Перевернутий портрет", ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
    FLAT("Лежить горизонтально", null)
}

/** Порогові значення (Threshold) системи Auto-Rotate Manager. */
object Thresholds {
    /** Мінімальна проєкція g на вісь X або Y (м/с²), щоб зафіксувати орієнтацію (~45°). */
    const val TILT = 7.0f
    /** Якщо |Z| більше цього значення — телефон лежить на столі (нахил < ~30°). */
    const val FLAT = 8.5f
    /** Скільки мс новий стан має утримуватися, перш ніж ми повернемо екран (гістерезис). */
    const val HOLD_TIME_MS = 400L
    /** Коефіцієнт низькочастотного фільтра (0..1): більше — плавніше, але повільніше. */
    const val ALPHA = 0.8f
}

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Стан, який читає Compose-інтерфейс (зміна значення → перемальовування UI)
    private var accX by mutableFloatStateOf(0f)
    private var accY by mutableFloatStateOf(0f)
    private var accZ by mutableFloatStateOf(0f)
    private var currentOrientation by mutableStateOf(DeviceOrientation.PORTRAIT)
    private var autoRotateEnabled by mutableStateOf(false)

    // Для гістерезису: кандидат на нову орієнтацію і час, з якого він тримається
    private var pendingOrientation: DeviceOrientation? = null
    private var pendingSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        // Отримуємо системний сервіс сенсорів і акселерометр
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AutoRotateScreen(
                        x = accX, y = accY, z = accZ,
                        orientation = currentOrientation,
                        sensorAvailable = accelerometer != null,
                        autoRotate = autoRotateEnabled,
                        onAutoRotateChange = { enabled ->
                            autoRotateEnabled = enabled
                            if (enabled) applyOrientation(currentOrientation)
                            else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        }
                    )
                }
            }
        }
    }

    /** Реєструємо слухача, коли активність видима. */
    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /** Знімаємо слухача, щоб сенсор не працював у фоні й не витрачав батарею. */
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    /** Викликається щоразу, коли акселерометр надсилає нові значення. */
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Низькочастотний фільтр: згладжуємо шум, залишаючи складову гравітації
        val a = Thresholds.ALPHA
        accX = a * accX + (1 - a) * event.values[0]
        accY = a * accY + (1 - a) * event.values[1]
        accZ = a * accZ + (1 - a) * event.values[2]

        val candidate = classify(accX, accY, accZ)

        // Проміжна зона (нахил ~45°) або той самий стан — нічого не змінюємо
        if (candidate == null || candidate == currentOrientation) {
            pendingOrientation = null
            return
        }

        // Гістерезис: новий стан має протриматися HOLD_TIME_MS
        val now = SystemClock.elapsedRealtime()
        if (candidate != pendingOrientation) {
            pendingOrientation = candidate
            pendingSince = now
        } else if (now - pendingSince >= Thresholds.HOLD_TIME_MS) {
            currentOrientation = candidate
            pendingOrientation = null
            applyOrientation(candidate)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Визначаємо орієнтацію за тим, на яку вісь «лягає» гравітація.
     * Повертає null, якщо пристрій у проміжному положенні.
     */
    private fun classify(x: Float, y: Float, z: Float): DeviceOrientation? = when {
        abs(z) > Thresholds.FLAT -> DeviceOrientation.FLAT
        abs(y) >= abs(x) -> when {
            y > Thresholds.TILT -> DeviceOrientation.PORTRAIT
            y < -Thresholds.TILT -> DeviceOrientation.UPSIDE_DOWN
            else -> null
        }
        else -> when {
            x > Thresholds.TILT -> DeviceOrientation.LANDSCAPE_LEFT
            x < -Thresholds.TILT -> DeviceOrientation.LANDSCAPE_RIGHT
            else -> null
        }
    }

    /** Повертаємо екран, якщо автоповорот увімкнено і стан не FLAT. */
    private fun applyOrientation(orientation: DeviceOrientation) {
        if (!autoRotateEnabled) return
        orientation.screenOrientation?.let { requestedOrientation = it }
    }
}

@Composable
fun AutoRotateScreen(
    x: Float, y: Float, z: Float,
    orientation: DeviceOrientation,
    sensorAvailable: Boolean,
    autoRotate: Boolean,
    onAutoRotateChange: (Boolean) -> Unit
) {
    // Кут нахилу від вертикалі в площині екрана (0° — телефон рівно в портреті)
    val angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Auto-Rotate Manager", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        if (!sensorAvailable) {
            Text("Акселерометр недоступний на цьому пристрої", color = MaterialTheme.colorScheme.error)
        }

        // Значення X, Y, Z
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Акселерометр, м/с²", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                listOf("X" to x, "Y" to y, "Z" to z).forEach { (name, value) ->
                    Text(
                        "$name = ${"%+.2f".format(value)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    )
                }
            }
        }

        // Поточний стан
        Text("Орієнтація: ${orientation.label}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        // Графічний індикатор
        OrientationIndicator(x = x, y = y, angle = angle)

        // Індикатор кута нахилу (0..90°)
        Column(Modifier.fillMaxWidth()) {
            Text("Нахил від вертикалі: ${"%.0f".format(abs(angle))}°")
            LinearProgressIndicator(
                progress = { min(abs(angle), 90f) / 90f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        // Перемикач автоповороту
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Керувати поворотом екрана")
            Switch(checked = autoRotate, onCheckedChange = onAutoRotateChange)
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

        // Зовнішнє коло
        drawCircle(color = outline, radius = r, style = Stroke(width = 4f))

        // Стрілка «верх», повернута на кут нахилу
        rotate(degrees = angle) {
            val tip = Offset(center.x, center.y - r * 0.8f)
            drawLine(primary, center, tip, strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(primary, tip, Offset(tip.x - 20f, tip.y + 28f), strokeWidth = 10f, cap = StrokeCap.Round)
            drawLine(primary, tip, Offset(tip.x + 20f, tip.y + 28f), strokeWidth = 10f, cap = StrokeCap.Round)
        }

        // Точка гравітації: проєкція вектора g на площину екрана
        val g = SensorManager.GRAVITY_EARTH
        val dot = Offset(
            center.x + (-x / g).coerceIn(-1f, 1f) * r,
            center.y + (y / g).coerceIn(-1f, 1f) * r
        )
        drawCircle(color = secondary, radius = 16f, center = dot)
    }
}