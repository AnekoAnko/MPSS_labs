package com.example.lab3_autorotatemanager.cloud

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * REST API Firebase Realtime Database.
 * POST /sensor_data/{deviceId}.json додає новий дочірній запис з унікальним ключем
 * і повертає {"name": "<ключ>"}.
 */
interface FirebaseApi {

    @POST("sensor_data/{deviceId}.json")
    suspend fun push(
        @Path("deviceId") deviceId: String,
        @Body data: SensorData,
        @Query("auth") auth: String? = null   // null → параметр не додається до URL
    ): Response<PushResponse>
}

/** Створення Retrofit-клієнта. */
object CloudModule {

    const val HTTP_TAG = "CloudSync-HTTP"

    /** Повертає null, якщо адресу бази не задано в local.properties. */
    fun createApi(baseUrl: String): FirebaseApi? {
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
            .create(FirebaseApi::class.java)
    }
}
