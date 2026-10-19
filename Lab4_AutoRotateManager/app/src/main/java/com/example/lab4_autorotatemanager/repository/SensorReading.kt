package com.example.lab4_autorotatemanager.repository

import kotlin.math.sqrt

/**
 * Модель, з якою працюють ViewModel і State: один вимір, який треба зберегти й відправити.
 *
 * Окремі моделі даних — і це нормально (методичка «Архітектура додатку: MVI»; у ЛР №5 додасться SensorRecord для Room):
 *  • SensorReading — те, з чим працює ViewModel (цей файл);
 *  • SensorPayload — як дані виглядають у JSON для хмари (CloudApi.kt).
 * Перетворення між ними — коротка функція toPayload() поруч із моделлю,
 * тому зміна формату JSON не зачіпає ViewModel і екран.
 */
data class SensorReading(
    /** Відфільтровані значення акселерометра, м/с². */
    val x: Float,
    val y: Float,
    val z: Float,
    /** Кут нахилу пристрою від вертикалі, градуси (DecisionEngine.tiltDegrees). */
    val tilt: Float,
    /** Стан Auto-Rotate Manager з ЛР №1 (PORTRAIT, FLAT, …). */
    val orientation: String,
    /** Чи спрацювала в цей момент система безпеки (актуатори ЛР №4). */
    val actionTriggered: Boolean = false,
    /** Час вимірювання, мс з 01.01.1970 (Unix epoch). */
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Модуль вектора прискорення |a|, м/с². */
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)
}

/** SensorReading → JSON для хмари (Firebase Realtime Database). */
fun SensorReading.toPayload(deviceId: String) = SensorPayload(
    deviceId = deviceId,
    type = SensorPayload.TYPE_ACCELEROMETER,
    x = x.round2(),
    y = y.round2(),
    z = z.round2(),
    value = magnitude.round2(),
    tilt = tilt.round1(),
    orientation = orientation,
    actionTriggered = actionTriggered,
    timestamp = timestamp
)

private fun Float.round2(): Float = Math.round(this * 100f) / 100f

private fun Float.round1(): Float = Math.round(this * 10f) / 10f
