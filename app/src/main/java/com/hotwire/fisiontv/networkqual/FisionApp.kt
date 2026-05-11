package com.hotwire.fisiontv.networkqual

import android.app.Application

/**
 * Application class. Hosts the process-wide [AppContainer] so every
 * other component (ViewModels, future workers, etc.) reads dependencies
 * from one canonical place rather than constructing them ad-hoc.
 */
class FisionApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
