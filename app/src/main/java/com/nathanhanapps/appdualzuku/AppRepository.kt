package com.nathanhanapps.appdualzuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

class AppRepository(private val context: Context) {

    enum class AppFilter {
        ALL,           // All apps (system + user)
        USER_ONLY,     // Only user-installed apps
        SYSTEM_ONLY    // Only system/preinstalled apps
    }

    fun loadInstalledAppsUser0(filter: AppFilter = AppFilter.ALL): List<AppItem> {
        val pm = context.packageManager

        // Get ALL installed applications for user 0
        val apps: List<ApplicationInfo> = pm.getInstalledApplications(
            PackageManager.GET_META_DATA
        )

        Log.d("AppRepository", "Total apps found: ${apps.size}")

        // Filter based on the filter type
        val filteredApps = when (filter) {
            AppFilter.ALL -> apps
            AppFilter.USER_ONLY -> {
                val userApps = apps.filter { isUserApp(it) }
                Log.d("AppRepository", "User apps count: ${userApps.size}")
                userApps.forEach {
                    Log.d("AppRepository", "User app: ${it.packageName} flags=${it.flags}")
                }
                userApps
            }
            AppFilter.SYSTEM_ONLY -> {
                val systemApps = apps.filter { isSystemApp(it) }
                Log.d("AppRepository", "System apps count: ${systemApps.size}")
                systemApps
            }
        }

        val items = filteredApps.map { ai ->
            val label = runCatching {
                pm.getApplicationLabel(ai).toString()
            }.getOrDefault(ai.packageName)

            val icon = runCatching {
                pm.getApplicationIcon(ai)
            }.getOrNull()

            AppItem(
                packageName = ai.packageName,
                label = label,
                icon = icon,
                isDual = false
            )
        }

        // Sort by label (case-insensitive)
        return items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /**
     * Check if an app is a user-installed app
     * User apps are:
     * 1. Apps without FLAG_SYSTEM (truly user-installed)
     * 2. Apps with FLAG_UPDATED_SYSTEM_APP (system apps updated by user)
     */
    private fun isUserApp(ai: ApplicationInfo): Boolean {
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        // User app if: NOT a system app, OR is an updated system app
        return !isSystem || isUpdatedSystem
    }

    /**
     * Check if an app is a system app (preinstalled)
     * System apps have FLAG_SYSTEM but are NOT updated by user
     */
    private fun isSystemApp(ai: ApplicationInfo): Boolean {
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        // System app if: has FLAG_SYSTEM AND NOT updated by user
        return isSystem && !isUpdatedSystem
    }
}