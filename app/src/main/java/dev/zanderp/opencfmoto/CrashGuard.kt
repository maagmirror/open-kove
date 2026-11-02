// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Keeps diagnostics alive across fatal crashes:
 *  - installs a process-wide uncaught-exception handler
 *  - writes the stack + [LogBus] snapshot to durable files under [Context.getFilesDir]
 *  - on next launch, reloads those into [LogBus] so Share Logs still works
 */
object CrashGuard {

    private const val CRASH_FILE = "last_crash.txt"
    private const val SESSION_FILE = "last_session.log"
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var installed = false
    @Volatile private var hydrated = false

    fun install(appContext: Context) {
        if (installed) return
        installed = true
        val app = appContext.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                recordFatal(app, thread, error)
            } catch (t: Throwable) {
                try {
                    Log.e(LogBus.TAG, "CrashGuard failed while recording", t)
                } catch (_: Throwable) {
                }
            }
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {
                }
                exitProcess(10)
            }
        }
    }

    /** Flush the in-memory log so a later kill/crash still has a session file. */
    fun persistSession(appContext: Context) {
        try {
            val text = LogBus.snapshot()
            if (text.isBlank()) return
            File(appContext.applicationContext.filesDir, SESSION_FILE).writeText(text)
        } catch (_: Exception) {
        }
    }

    /**
     * On cold start: pull the previous session + crash report into [LogBus] so the on-screen log
     * and Share Logs show what happened before the death. Safe to call once per process.
     * Returns true when a crash report was pending.
     */
    fun hydrateLogBus(appContext: Context): Boolean {
        if (hydrated) return pendingCrashText(appContext) != null
        hydrated = true
        val app = appContext.applicationContext
        val hadCrash = crashFile(app).exists() && crashFile(app).length() > 0L
        try {
            val session = File(app.filesDir, SESSION_FILE)
            if (session.exists() && session.length() > 0L) {
                LogBus.restore(
                    "--- restored session log (saved before last exit/crash) ---\n" +
                        session.readText().trimEnd() + "\n",
                )
            }
            val crash = pendingCrashText(app)
            if (crash != null) {
                LogBus.log("--- previous fatal crash (also in files/last_crash.txt) ---")
                for (line in crash.lineSequence()) {
                    if (line.isNotEmpty()) LogBus.log(line)
                }
                LogBus.log("--- end previous crash — use Share Logs to send this ---")
            }
        } catch (e: Exception) {
            try {
                LogBus.log("[crash] hydrate failed: $e")
            } catch (_: Exception) {
            }
        }
        return hadCrash
    }

    fun pendingCrashText(appContext: Context): String? {
        val f = crashFile(appContext.applicationContext)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            f.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun crashFile(appContext: Context): File =
        File(appContext.applicationContext.filesDir, CRASH_FILE)

    fun clearCrash(appContext: Context) {
        try {
            crashFile(appContext).delete()
        } catch (_: Exception) {
        }
    }

    private fun recordFatal(app: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()
        val header = buildString {
            append("=== OpenCfMoto FATAL ")
            append(stampFmt.format(Date()))
            append(" ===\n")
            append("version=").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("thread=").append(thread.name).append('\n')
            append(stack.trimEnd()).append('\n')
        }
        // Best-effort: also stamp LogBus so the snapshot includes a final line.
        try {
            LogBus.log("[FATAL] ${error.javaClass.name}: ${error.message}")
        } catch (_: Exception) {
        }
        val snapshot = try {
            LogBus.snapshot()
        } catch (_: Exception) {
            ""
        }
        val body = header + "\n=== LogBus snapshot ===\n" + snapshot
        File(app.filesDir, CRASH_FILE).writeText(body)
        if (snapshot.isNotBlank()) {
            File(app.filesDir, SESSION_FILE).writeText(snapshot)
        }
        Log.e(LogBus.TAG, "FATAL on ${thread.name}", error)
    }
}
