package com.example.lab7_autorotatemanager

import android.app.Application
import com.example.lab7_autorotatemanager.di.AppContainer
import com.example.lab7_autorotatemanager.di.DefaultAppContainer

/**
 * Клас Application: створюється раніше за будь-яку Activity і живе, поки живе процес.
 * ЛР №7: замість глобального об'єкта AppGraph (Service Locator) тримає контейнер залежностей.
 */
class AutoRotateApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
