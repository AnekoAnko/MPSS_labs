package com.example.lab6_autorotatemanager.repository

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** REST API Firebase Realtime Database (Retrofit). */
interface CloudApi {

    /** ЛР №3: POST додає новий дочірній запис з унікальним ключем і повертає {"name": "<ключ>"}. */
    @POST("sensor_data/{deviceId}.json")
    suspend fun push(
        @Path("deviceId") deviceId: String,
        @Body data: SensorPayload,
        @Query("auth") auth: String? = null   // null → параметр не додається до URL
    ): Response<PushResponse>

    /** ЛР №4: PUT замінює вузол status/{deviceId} цілком — фактичний стан актуаторів. */
    @PUT("status/{deviceId}.json")
    suspend fun putStatus(
        @Path("deviceId") deviceId: String,
        @Body report: ActuatorReport,
        @Query("auth") auth: String? = null
    ): Response<ActuatorReport>
}

/**
 * Як один вимір виглядає у JSON для хмари (гілка sensor_data/{deviceId}).
 * Створюється лише з SensorReading.toPayload() — ViewModel цю модель не бачить.
 */
data class SensorPayload(
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
    /** Кут нахилу від вертикалі, градуси. */
    val tilt: Float,
    /** Стан Auto-Rotate Manager з ЛР №1 (PORTRAIT, FLAT, …). */
    val orientation: String,
    /** Чи спрацювала система безпеки (ЛР №4). */
    val actionTriggered: Boolean,
    /** Час вимірювання, мс з 01.01.1970 (Unix epoch). */
    val timestamp: Long
) {
    companion object {
        const val TYPE_ACCELEROMETER = "ACCELEROMETER"
    }
}

/** Відповідь Firebase Realtime Database на POST: ключ створеного запису. */
data class PushResponse(val name: String?)

/**
 * ЛР №4 — зворотний зв'язок: фактичний стан актуаторів, який додаток записує в status/{deviceId}.
 * Так у консолі Firebase видно, що команда з control/ дійсно виконана.
 */
data class ActuatorReport(
    val flashlight: Boolean,
    val flashlightHardware: Boolean,
    val siren: Boolean,
    val lamp: Boolean,
    val alarm: Boolean,
    val tilt: Float,
    val threshold: Float,
    val lastSource: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Створення Retrofit-клієнта. */
object CloudModule {

    const val HTTP_TAG = "CloudSync-HTTP"

    /** Повертає null, якщо адресу бази не задано в local.properties. */
    fun createApi(baseUrl: String): CloudApi? {
        if (baseUrl.isBlank()) return null
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        // Логи Retrofit/OkHttp: метод, URL, тіло JSON, код відповіді (Logcat, тег CloudSync-HTTP)
        val logging = HttpLoggingInterceptor { message -> Log.d(HTTP_TAG, message) }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudApi::class.java)
    }
}
