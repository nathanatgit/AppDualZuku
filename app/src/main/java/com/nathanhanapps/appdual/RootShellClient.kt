package com.nathanhanapps.appdual

import android.content.Context
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs shell commands directly through `su`, bypassing Shizuku/ADB entirely.
 *
 * Unlike [ShellClient] (which forwards commands to a Shizuku UserService running at
 * shell/ADB UID), this spawns `su` itself. The su binary is what performs the privilege
 * escalation (granted by Magisk/KernelSU/etc. to this app's UID) - no binder, no ADB.
 *
 * A single `su` process is kept alive and commands are piped through it one at a time,
 * so repeated pm/am calls don't re-trigger the root manager's grant prompt each time.
 */
class RootShellClient(private val context: Context) : IShellExecutor {

    // Serializes writes/reads against the single persistent su process.
    private val cmdExecutor = Executors.newSingleThreadExecutor()

    // Used only to enforce read timeouts without blocking cmdExecutor itself.
    private val ioWait = Executors.newCachedThreadPool()

    private var process: Process? = null
    private var stdin: DataOutputStream? = null
    private var reader: BufferedReader? = null
    private val ready = AtomicBoolean(false)

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 60_000L // room for the Magisk/KernelSU grant prompt
        private const val CMD_TIMEOUT_MS = 15_000L

        /** One-off, non-persistent probe: does `su` grant root right now? Blocks - call off the UI thread. */
        fun isRootAvailable(): Boolean {
            return try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val finished = p.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    runCatching { p.destroy() }
                    return false
                }
                val out = p.inputStream.bufferedReader().readText()
                p.exitValue() == 0 && out.contains("uid=0")
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun readLineWithTimeout(br: BufferedReader, timeoutMs: Long): String? {
        val future = ioWait.submit<String?> { br.readLine() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    /** Starts `su` and confirms it actually granted root. Must run off the cmdExecutor thread that will use it. */
    private fun ensureStarted(): Boolean {
        if (ready.get()) return true

        return try {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            val out = DataOutputStream(p.outputStream)
            val br = BufferedReader(InputStreamReader(p.inputStream))

            out.writeBytes("id\n")
            out.flush()

            val line = readLineWithTimeout(br, HANDSHAKE_TIMEOUT_MS)
            if (line == null || !line.contains("uid=0")) {
                DebugLog.trace(context, "RootShellClient: root grant denied/unavailable (got: $line)")
                runCatching { p.destroy() }
                return false
            }

            process = p
            stdin = out
            reader = br
            ready.set(true)
            DebugLog.trace(context, "RootShellClient: su shell ready ($line)")
            true
        } catch (t: Throwable) {
            DebugLog.trace(context, "RootShellClient: failed to start su: ${t.message}")
            false
        }
    }

    private fun teardown() {
        ready.set(false)
        runCatching { stdin?.writeBytes("exit\n"); stdin?.flush() }
        runCatching { reader?.close() }
        runCatching { stdin?.close() }
        runCatching { process?.destroy() }
        process = null
        stdin = null
        reader = null
    }

    private fun runCommandBlocking(cmd: String): String {
        if (!ready.get() && !ensureStarted()) {
            return "ERROR: root (su) not available or permission was denied"
        }

        val out = stdin
        val br = reader
        if (out == null || br == null) return "ERROR: root shell not initialized"

        val marker = "RSC_${System.nanoTime()}"
        return try {
            out.writeBytes(cmd + "\n")
            out.writeBytes("echo " + marker + ":\$?\n")
            out.flush()

            val output = StringBuilder()
            var exitCode: Int? = null
            val deadline = System.currentTimeMillis() + CMD_TIMEOUT_MS
            var timedOut = false

            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    timedOut = true
                    break
                }

                // A null read means either the wait itself timed out, or the process died (EOF).
                val line = readLineWithTimeout(br, remaining) ?: run { timedOut = true; null }
                if (line == null) break

                val markerPrefix = "$marker:"
                if (line.startsWith(markerPrefix)) {
                    exitCode = line.removePrefix(markerPrefix).trim().toIntOrNull()
                    break
                }
                output.append(line).append('\n')
            }

            if (timedOut) {
                teardown() // shell may be wedged - drop it, next call restarts fresh
                "exitCode=TIMEOUT\ncmd=$cmd"
            } else {
                buildString {
                    if (exitCode != null) append("exitCode=").append(exitCode).append('\n')
                    val body = output.toString().trim()
                    if (body.isNotEmpty()) append("stdout:\n").append(body).append('\n')
                }.trim()
            }
        } catch (t: Throwable) {
            teardown()
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun execWhenReady(cmd: String, callback: (String) -> Unit) {
        DebugLog.trace(context, "RootShellClient.execWhenReady(): cmd=$cmd")
        cmdExecutor.execute {
            val result = runCommandBlocking(cmd)
            callback(result)
        }
    }

    override fun isReady(): Boolean = ready.get()

    override fun unbind() {
        cmdExecutor.execute {
            teardown()
            cmdExecutor.shutdown()
        }
        ioWait.shutdown()
    }
}
