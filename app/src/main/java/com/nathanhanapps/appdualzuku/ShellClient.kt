package com.nathanhanapps.appdualzuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ShellClient(private val context: Context) {
    @Volatile
    private var service: IRunCmdService? = null
    @Volatile
    private var binding = false

    private val pending = CopyOnWriteArrayList<Pair<String, (String) -> Unit>>()
    private val bg = Executors.newSingleThreadExecutor()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            DebugLog.trace(context, "ShellClient: onServiceConnected")
            if (binder != null) {
                service = IRunCmdService.Stub.asInterface(binder)
                binding = false

                // Flush queued commands
                val tasks = pending.toList()
                pending.clear()
                tasks.forEach { (cmd, cb) ->
                    exec(cmd, cb)
                }
            } else {
                DebugLog.trace(context, "Binder is null, failed to bind service.")
                binding = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DebugLog.trace(context, "ShellClient: onServiceDisconnected")
            service = null
            binding = false
        }
    }

    // Automatically bind when ShellClient is created
    init {
        DebugLog.trace(context, "ShellClient: init - auto-binding")
        bind()
    }

    fun bind() {
        if (service == null && !binding) {
            binding = true
            DebugLog.trace(context, "Binding to Shizuku service...")

            try {
                // Create the connection arguments with the correct service component
                val userServiceArgs = UserServiceArgs(
                    ComponentName(context.packageName, RunCmdUserService::class.java.name)
                )
                    .daemon(false) // Don't run as daemon
                    .processNameSuffix("runcmd") // Optional: custom process name
                    .debuggable(BuildConfig.DEBUG) // Enable debugging in debug builds
                    .version(BuildConfig.VERSION_CODE) // Service version

                // Bind using Shizuku's bindUserService method
                Shizuku.bindUserService(userServiceArgs, conn)
            } catch (e: Exception) {
                DebugLog.trace(context, "Error binding service: ${e.message}")
                binding = false
            }
        } else {
            DebugLog.trace(context, "Already bound or binding in progress. service=$service binding=$binding")
        }
    }

    fun unbind() {
        DebugLog.trace(context, "ShellClient.unbind() called")

        if (service != null || binding) {
            try {
                val userServiceArgs = UserServiceArgs(
                    ComponentName(context.packageName, RunCmdUserService::class.java.name)
                )
                Shizuku.unbindUserService(userServiceArgs, conn, true)
                DebugLog.trace(context, "Service unbound successfully")
            } catch (e: Exception) {
                DebugLog.trace(context, "Error unbinding: ${e.message}")
            }
        }

        service = null
        binding = false
        pending.clear()
    }

    /** Always calls callback. If not connected yet, queues and waits. */
    fun execWhenReady(cmd: String, callback: (String) -> Unit) {
        DebugLog.trace(context, "execWhenReady: cmd=$cmd serviceNull=${service == null} binding=$binding")

        // If already connected, run now
        val sNow = service
        if (sNow != null) {
            exec(cmd, callback)
            return
        }

        // Queue the command
        pending.add(cmd to callback)

        // Ensure we're trying to bind (might already be in progress)
        if (!binding && service == null) {
            bind()
        }

        // Timeout watchdog (runs on background executor)
        bg.execute {
            val start = System.currentTimeMillis()
            val timeout = 3000L // 3 seconds timeout

            while (service == null && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
            }

            if (service == null) {
                DebugLog.trace(context, "execWhenReady: timeout after ${timeout}ms, failing pending callbacks")

                // Find and remove this specific command from pending
                val toFail = pending.filter { it.first == cmd }
                pending.removeAll(toFail)

                toFail.forEach { (_, cb) ->
                    cb("ERROR: Shizuku UserService not connected (timeout after ${timeout}ms)")
                }
            }
        }
    }

    fun exec(cmd: String, callback: (String) -> Unit) {
        DebugLog.trace(context, "ShellClient.exec(): cmd=$cmd serviceNull=${service == null}")

        val s = service
        if (s == null) {
            DebugLog.trace(context, "Service not connected, cannot execute command")
            callback("ERROR: service not connected yet")
            return
        }

        bg.execute {
            val result = try {
                DebugLog.trace(context, "Executing command: $cmd")
                s.run(cmd)
            } catch (e: RemoteException) {
                val msg = "ERROR: RemoteException: ${e.message}"
                DebugLog.trace(context, msg)
                msg
            } catch (t: Throwable) {
                val msg = "ERROR: ${t.javaClass.simpleName}: ${t.message}"
                DebugLog.trace(context, msg)
                msg
            }
            callback(result)
        }
    }

    fun isServiceConnected(): Boolean {
        val connected = service != null
        DebugLog.trace(context, "isServiceConnected(): $connected")
        return connected
    }

    fun isBinding(): Boolean {
        return binding
    }
}