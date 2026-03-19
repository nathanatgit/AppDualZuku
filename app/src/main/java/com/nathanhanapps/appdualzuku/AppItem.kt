package com.nathanhanapps.appdualzuku

import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    /** Set of non-main user IDs where this package is installed. */
    val installedUserIds: Set<Int> = emptySet()
) {
    /** True if installed in at least one non-main workspace. */
    val isDual: Boolean get() = installedUserIds.isNotEmpty()
    val workspaceCount: Int get() = installedUserIds.size
}
