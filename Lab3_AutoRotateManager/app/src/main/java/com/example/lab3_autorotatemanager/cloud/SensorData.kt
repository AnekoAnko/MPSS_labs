package com.example.lab3_autorotatemanager.cloud

/**
 * Модель даних, яка передається в хмару у форматі JSON.
 * Поєднує поля з методички: SensorData (deviceId, timestamp, value, type)
 * і SensorPayload (sensorType, x, y, z, timestamp).
 */
data class SensorData(
    /** Ідентифікатор пристрою (розумного датчика). */
    val deviceId: String,
    /** Тип сенсора: ACCELEROMETER. */
    val type: String,
    /** Проєкції прискорення на осі, м/с². */
    val x: Float,
    val y: Float,
    val z: Float,
    /** Узагальнене значення: модуль вектора прискорення |a|, м/с². */
    val value: Float,
    /** Стан Auto-Rotate Manager з ЛР №1 (PORTRAIT, FLAT, …). */
    val orientation: String,
    /** Час вимірювання, мс з 01.01.1970 (Unix epoch). */
    val timestamp: Long = System.currentTimeMillis()
)

/** Відповідь Firebase Realtime Database на POST: ключ створеного запису. */
data class PushResponse(val name: String?)
