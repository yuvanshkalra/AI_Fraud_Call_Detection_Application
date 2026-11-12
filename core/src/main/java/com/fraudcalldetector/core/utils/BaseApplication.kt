package com.fraudcalldetector.core.utils

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

// Re-exported for app module to subclass — keeps core self-contained
open class BaseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
