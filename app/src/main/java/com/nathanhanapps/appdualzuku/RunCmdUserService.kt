package com.nathanhanapps.appdualzuku

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * IMPORTANT: Shizuku user services must NOT extend Service.
 * They should be plain classes that extend the AIDL Stub.
 */
class RunCmdUserService : IRunCmdService.Stub() {

    override fun run(cmd: String): String {
        return runShell(cmd)
    }

    /**
     * This method is called by Shizuku to destroy the service
     */
    override fun destroy() {
        // Cleanup if needed
        System.exit(0)
    }

    private fun runShell(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(false)
                .start()

            // Avoid hanging forever
            val finished = p.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { p.destroy() }
                return "exitCode=TIMEOUT\ncmd=$cmd"
            }

            val stdout = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(p.errorStream)).readText()
            val code = p.exitValue()

            buildString {
                append("exitCode=").append(code).append('\n')
                if (stdout.isNotBlank()) append("stdout:\n").append(stdout.trim()).append('\n')
                if (stderr.isNotBlank()) append("stderr:\n").append(stderr.trim()).append('\n')
            }.trim()
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}