package com.example.lab7_autorotatemanager

import android.app.Application
import com.example.lab7_autorotatemanager.di.AppContainer
import com.example.lab7_autorotatemanager.di.DefaultAppContainer

/** Application class: creates the dependency container once per process. */
class AutoRotateApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
