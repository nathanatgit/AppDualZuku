package com.nathanhanapps.appdualzuku

object WorkspaceParsers {

    // Matches: UserInfo{15:Work2:1020} running
    private val USER_REGEX = Regex("""UserInfo\{(\d+):([^:}]*):([0-9a-fA-F]+)\}(.*)""")

    fun parseUsers(output: String): List<WorkspaceInfo> =
        output.lineSequence()
            .mapNotNull { line ->
                val m = USER_REGEX.find(line.trim()) ?: return@mapNotNull null
                val (idStr, name, flagsHex, rest) = m.destructured
                val userId = idStr.toIntOrNull() ?: return@mapNotNull null
                WorkspaceInfo(
                    userId  = userId,
                    name    = name.trim(),
                    flags   = flagsHex.toIntOrNull(16) ?: 0,
                    isRunning = rest.contains("running", ignoreCase = true)
                )
            }
            .sortedBy { it.userId }
            .toList()
}
