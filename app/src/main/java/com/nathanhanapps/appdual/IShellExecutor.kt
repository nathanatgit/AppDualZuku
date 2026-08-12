package com.nathanhanapps.appdual

/**
 * Anything that can run a shell command and report back the combined output.
 * Implemented by [ShellClient] (Shizuku/ADB shell) and [RootShellClient] (su).
 */
interface IShellExecutor {
    /** Runs [cmd], queuing it if the backing shell isn't ready yet. Always calls [callback]. */
    fun execWhenReady(cmd: String, callback: (String) -> Unit)

    /** True once the backing shell is connected and able to run commands immediately. */
    fun isReady(): Boolean

    /** Tears down the backing shell/process. */
    fun unbind()
}
