package com.example.lab4_autorotatemanager.control

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Зворотний зв'язок (feedback): фактичний стан актуаторів, який додаток записує в status/{deviceId}.
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

interface ControlFeedbackApi {

    /** PUT замінює вузол цілком (на відміну від POST з ЛР №3, який додає новий запис). */
    @PUT("status/{deviceId}.json")
    suspend fun putStatus(
        @Path("deviceId") deviceId: String,
        @Body report: ActuatorReport,
        @Query("auth") auth: String? = null
    ): Response<ActuatorReport>
}

object ControlModule {

    const val HTTP_TAG = "Control-HTTP"

    /** Повертає null, якщо адресу бази не задано в local.properties. */
    fun createFeedbackApi(baseUrl: String): ControlFeedbackApi? {
        if (baseUrl.isBlank()) return null
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val logging = HttpLoggingInterceptor { message -> Log.d(HTTP_TAG, message) }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ControlFeedbackApi::class.java)
    }
}
