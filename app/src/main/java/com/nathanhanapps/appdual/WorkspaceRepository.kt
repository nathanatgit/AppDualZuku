package com.nathanhanapps.appdual

class WorkspaceRepository(private val shell: IShellExecutor) {

    fun listWorkspaces(callback: (List<WorkspaceInfo>) -> Unit) {
        shell.execWhenReady("pm list users") { output ->
            callback(WorkspaceParsers.parseUsers(output))
        }
    }

    /**
     * Creates a profile cloned from user 0.
     * [type] can be "managed" or "clone".
     */
    fun createWorkspace(name: String, type: String = "managed", callback: (Boolean, Int, String) -> Unit) {
        val safe = name.replace("\"", "").trim()
        val cmd = if (type == "clone") {
            "pm create-user --profileOf 0 --user-type android.os.usertype.profile.CLONE \"$safe\""
        } else {
            "pm create-user --profileOf 0 --managed \"$safe\""
        }
        
        shell.execWhenReady(cmd) { output ->
            // Typical success line: "Success: created user id 15"
            val userId = Regex("""created user id (\d+)""", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            
            val success = output.contains("Success", ignoreCase = true) && userId != -1
            callback(success, userId, output)
        }
    }

    fun removeWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("pm remove-user $userId") { out ->
            callback(out.contains("Success", ignoreCase = true), out)
        }
    }

    fun startWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am start-user $userId") { out ->
            callback(!out.startsWith("ERROR:") && !out.contains("failed", ignoreCase = true), out)
        }
    }

    fun stopWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am stop-user -f $userId") { out ->
            callback(!out.startsWith("ERROR:") && !out.contains("failed", ignoreCase = true), out)
        }
    }

    fun getInstalledPackages(userId: Int, callback: (Set<String>) -> Unit) {
        shell.execWhenReady("pm list packages --user $userId") { output ->
            callback(PmParsers.parsePmListPackages(output))
        }
    }

    fun installToWorkspace(userId: Int, packageName: String, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("pm install-existing --user $userId $packageName") { out ->
            val ok = out.contains("Package", ignoreCase = true) &&
                    out.contains("installed", ignoreCase = true)
            callback(ok, out)
        }
    }

    fun uninstallFromWorkspace(userId: Int, packageName: String, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("pm uninstall --user $userId $packageName") { out ->
            callback(out.contains("Success", ignoreCase = true), out)
        }
    }

    fun launchInWorkspace(userId: Int, component: String, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am start --user $userId -n $component") { out ->
            callback(!out.contains("Error", ignoreCase = true), out)
        }
    }

    fun openAppInfoInWorkspace(userId: Int, packageName: String, callback: (Boolean, String) -> Unit) {
        val cmd = "am start --user $userId -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName"
        shell.execWhenReady(cmd) { out ->
            callback(!out.startsWith("ERROR:") && !out.contains("failed", ignoreCase = true), out)
        }
    }

    /** Suggests "Work1", "Work2", etc. avoiding names already taken. */
    fun suggestName(existing: List<WorkspaceInfo>, prefix: String = "Work"): String {
        val taken = existing.filter { !it.isMainUser }.map { it.name }.toSet()
        var i = 1
        while ("$prefix$i" in taken) i++
        return "$prefix$i"
    }

}
