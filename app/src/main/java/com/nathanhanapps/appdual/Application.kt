package com.nathanhanapps.appdual

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Enable Material 3 dynamic (wallpaper-based) colors
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Some OEM ROMs (e.g. nubia/ZTE) ship with logcat disabled, so persist
        // uncaught exceptions to files/crash.txt as a fallback we can pull via `run-as`.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLog.writeCrash(this, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
