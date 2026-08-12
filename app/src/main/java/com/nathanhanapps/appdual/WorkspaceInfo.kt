package com.nathanhanapps.appdual

data class WorkspaceInfo(
    val userId: Int,
    val name: String,
    val flags: Int,
    val isRunning: Boolean
) {
    val isMainUser: Boolean get() = userId == 0
    val displayName: String get() = name.ifBlank { "User $userId" }
}
