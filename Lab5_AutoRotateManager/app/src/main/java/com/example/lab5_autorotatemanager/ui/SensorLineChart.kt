package com.example.lab5_autorotatemanager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.example.lab5_autorotatemanager.data.SensorRecord
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ALARM_COLOR = android.graphics.Color.rgb(198, 40, 40)

/**
 * ЛР №5: лінійний графік (MPAndroidChart LineChart) у Compose через AndroidView.
 * Вісь X — час запису (HH:mm:ss), вісь Y — значення сенсора (кут нахилу, 0..180°).
 * Червоні точки — записи з actionTriggered = true (спрацювання системи безпеки),
 * пунктир — поточний поріг DecisionEngine.
 *
 * factory викликається один раз (створення View), update — після кожної зміни
 * списку points, тобто після кожного нового рядка в таблиці.
 */
@Composable
fun SensorLineChart(
    points: List<SensorRecord>,
    threshold: Float,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context -> LineChart(context).also { setupChart(it, textColor) } },
        update = { chart -> renderChart(chart, points, threshold, lineColor) }
    )
}

/** Одноразове налаштування вигляду графіка. */
private fun setupChart(chart: LineChart, textColor: Int) {
    chart.description.isEnabled = false
    chart.legend.isEnabled = false
    chart.setNoDataText("Немає даних — покрутіть пристрій")
    chart.setTouchEnabled(true)
    chart.setScaleEnabled(false)
    chart.setPinchZoom(false)
    chart.axisRight.isEnabled = false

    chart.axisLeft.axisMinimum = 0f
    chart.axisLeft.axisMaximum = 180f
    chart.axisLeft.granularity = 30f
    chart.axisLeft.textColor = textColor

    chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
    chart.xAxis.setDrawGridLines(false)
    chart.xAxis.granularity = 1f
    chart.xAxis.setLabelCount(4, false)
    chart.xAxis.textColor = textColor
}

/** Перебудова даних графіка з актуального списку записів. */
private fun renderChart(chart: LineChart, points: List<SensorRecord>, threshold: Float, lineColor: Int) {
    // Поріг DecisionEngine — горизонтальна пунктирна лінія
    chart.axisLeft.removeAllLimitLines()
    chart.axisLeft.addLimitLine(LimitLine(threshold, "Поріг ${threshold.toInt()}°").apply {
        setLineColor(ALARM_COLOR)
        setLineWidth(1.5f)
        enableDashedLine(12f, 8f, 0f)
        setTextColor(ALARM_COLOR)
        setTextSize(10f)
    })

    if (points.isEmpty()) {
        chart.clear()   // показує setNoDataText
        return
    }

    // Float не вміщує мілісекунди epoch (≈1,8·10¹²) без втрати точності,
    // тому по X відкладаємо секунди від першої точки, а підпис рахуємо назад у час.
    val base = points.first().timestamp
    val entries = points.map { Entry((it.timestamp - base) / 1000f, it.sensorValue) }

    val dataSet = LineDataSet(entries, "Нахил, °").apply {
        setColor(lineColor)
        setLineWidth(2f)
        setDrawValues(false)
        setMode(LineDataSet.Mode.LINEAR)
        setDrawCircles(true)
        setDrawCircleHole(false)
        setCircleRadius(3f)
        setCircleColors(points.map { if (it.actionTriggered) ALARM_COLOR else lineColor })
        setDrawFilled(true)
        setFillColor(lineColor)
        setFillAlpha(40)
        setHighlightEnabled(false)
    }

    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    chart.xAxis.setValueFormatter(object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String =
            timeFormat.format(Date(base + (value * 1000f).toLong()))
    })

    chart.setData(LineData(dataSet))
    chart.notifyDataSetChanged()
    chart.invalidate()   // перемалювати без анімації — «живий» графік
}
