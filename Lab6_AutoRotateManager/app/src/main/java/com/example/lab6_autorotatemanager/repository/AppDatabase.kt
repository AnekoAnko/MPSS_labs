package com.example.lab6_autorotatemanager.repository

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ЛР №5: база даних Room (абстракція над SQLite).
 * Синглтон: на весь процес створюється одне з'єднання з файлом auto_rotate.db,
 * бо відкриття бази — дорога операція, а кілька екземплярів можуть конфліктувати.
 */
@Database(
    entities = [SensorRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sensorDao(): SensorDao

    companion object {
        const val DB_NAME = "auto_rotate.db"

        /** @Volatile — зміна поля одразу видима всім потокам. */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Double-checked locking: створюємо базу лише один раз, навіть якщо виклики з різних потоків. */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,   // не Activity — щоб не було витоку пам'яті
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // Під час розробки схема змінюється; у навчальному проєкті простіше перестворити базу
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
