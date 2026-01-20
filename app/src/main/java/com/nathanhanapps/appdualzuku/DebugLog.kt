package com.nathanhanapps.appdualzuku

import android.content.Context
import android.util.Log

object DebugLog {
    private const val TRACE = "trace.txt"
    private const val CRASH = "crash.txt"

    private const val TAG = "DebugLog"

    fun trace(ctx: Context, msg: String) {
        try {
            ctx.openFileOutput(TRACE, Context.MODE_APPEND).use {
                it.write((msg + "\n").toByteArray())
            }
            // Log to Logcat as well
            Log.d(TAG, msg)  // Using Log.d for Debug
        } catch (_: Throwable) {
            // Handle exception
        }
    }

    fun writeCrash(ctx: Context, t: Throwable) {
        try {
            ctx.openFileOutput(CRASH, Context.MODE_PRIVATE).use {
                it.write(t.stackTraceToString().toByteArray())
            }
            // Log the stack trace to Logcat
            Log.e(TAG, "Crash occurred", t)  // Using Log.e for errors
        } catch (_: Throwable) {
            // Handle exception
        }
    }

    fun readTrace(ctx: Context): String =
        runCatching { ctx.openFileInput(TRACE).bufferedReader().readText() }.getOrDefault("")

    fun readCrash(ctx: Context): String =
        runCatching { ctx.openFileInput(CRASH).bufferedReader().readText() }.getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { ctx.deleteFile(TRACE) }
        runCatching { ctx.deleteFile(CRASH) }
    }
}
