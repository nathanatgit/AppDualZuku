package com.nathanhanapps.appdual

import android.content.Context

object Prefs {
    private const val FILE = "appdual_prefs"
    private const val KEY_USE_ROOT = "use_root"

    fun useRoot(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_USE_ROOT, false)

    fun setUseRoot(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_USE_ROOT, value)
            .apply()
    }
}
