package com.example.lab6_autorotatemanager.repository

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database (SQLite). Singleton: opening a database is expensive
 * and multiple instances could conflict.
 */
@Database(
    entities = [SensorRecord::class],
    version = 2,   // v2: x, y, z, synced columns (upload queue)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sensorDao(): SensorDao

    companion object {
        const val DB_NAME = "auto_rotate.db"

        /** Writes are immediately visible to all threads. */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Double-checked locking: created once even with concurrent calls. */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,   // application context avoids leaking an Activity
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // Schema still changes during development; recreating the DB is simpler
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
