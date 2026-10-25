package com.example.lab7_autorotatemanager.repository

import kotlin.math.sqrt

/**
 * Модель, з якою працюють ViewModel і State: один вимір, який треба зберегти й відправити.
 *
 * Три моделі даних — і це нормально (див. методичку «Архітектура додатку: MVI»):
 *  • SensorReading — те, з чим працює ViewModel (цей файл);
 *  • SensorPayload — як дані виглядають у JSON для хмари (CloudApi.kt);
 *  • SensorRecord  — рядок таблиці Room (SensorRecord.kt, ЛР №5).
 * Перетворення між ними — короткі функції toPayload()/toRecord() поруч із моделями,
 * тому зміна формату JSON не ламає базу, і навпаки.
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
    /** ЛР №7: підтверджений стан з ML-моделі ЛР №6 (STILL, WALKING, RUNNING, ANOMALY). */
    val activity: String = "STILL",
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
    activity = activity,
    timestamp = timestamp
)

/** SensorReading → рядок таблиці Room. synced = false: у хмару запис ще не відправлено. */
fun SensorReading.toRecord() = SensorRecord(
    timestamp = timestamp,
    sensorValue = tilt.round1(),
    actionTriggered = actionTriggered,
    orientation = orientation,
    x = x.round2(),
    y = y.round2(),
    z = z.round2(),
    activity = activity,
    synced = false
)

/** Рядок Room → SensorReading (щоб відправити в хмару те, що не пішло одразу). */
fun SensorRecord.toReading() = SensorReading(
    x = x,
    y = y,
    z = z,
    tilt = sensorValue,
    orientation = orientation,
    actionTriggered = actionTriggered,
    activity = activity,
    timestamp = timestamp
)

private fun Float.round2(): Float = Math.round(this * 100f) / 100f

private fun Float.round1(): Float = Math.round(this * 10f) / 10f
