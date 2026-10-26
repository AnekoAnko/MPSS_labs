package com.example.lab4_autorotatemanager.repository

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

/** Firebase Realtime Database REST API (Retrofit). */
interface CloudApi {

    /** POST adds a child with a generated key and returns {"name": "<key>"}. */
    @POST("sensor_data/{deviceId}.json")
    suspend fun push(
        @Path("deviceId") deviceId: String,
        @Body data: SensorPayload,
        @Query("auth") auth: String? = null   // null → parameter is omitted
    ): Response<PushResponse>

    /** PUT replaces status/{deviceId} with the actual actuator state. */
    @PUT("status/{deviceId}.json")
    suspend fun putStatus(
        @Path("deviceId") deviceId: String,
        @Body report: ActuatorReport,
        @Query("auth") auth: String? = null
    ): Response<ActuatorReport>
}

/** JSON shape of one sample in sensor_data/{deviceId}. Built only via SensorReading.toPayload(). */
data class SensorPayload(
    /** Device id. */
    val deviceId: String,
    /** Sensor type: ACCELEROMETER. */
    val type: String,
    /** Acceleration per axis, m/s². */
    val x: Float,
    val y: Float,
    val z: Float,
    /** Magnitude |a|, m/s². */
    val value: Float,
    /** Tilt from vertical, degrees. */
    val tilt: Float,
    /** Device orientation (PORTRAIT, FLAT, …). */
    val orientation: String,
    /** Whether the alarm fired at this moment. */
    val actionTriggered: Boolean,
    /** Sample time, epoch ms. */
    val timestamp: Long
) {
    companion object {
        const val TYPE_ACCELEROMETER = "ACCELEROMETER"
    }
}

/** Firebase reply to POST: key of the created record. */
data class PushResponse(val name: String?)

/** Actual actuator state written to status/{deviceId}, so the console shows that a command was executed. */
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

/** Builds the Retrofit client. */
object CloudModule {

    const val HTTP_TAG = "CloudSync-HTTP"

    /** Returns null if the database URL is not set in local.properties. */
    fun createApi(baseUrl: String): CloudApi? {
        if (baseUrl.isBlank()) return null
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        // HTTP logs: method, URL, JSON body, status (Logcat tag CloudSync-HTTP)
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
