package com.nathanhanapps.appdualzuku

import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    var isDual: Boolean = false
)
