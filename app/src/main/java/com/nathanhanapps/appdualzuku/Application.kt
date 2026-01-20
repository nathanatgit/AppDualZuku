package com.nathanhanapps.appdualzuku

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Enable Material 3 dynamic (wallpaper-based) colors
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
