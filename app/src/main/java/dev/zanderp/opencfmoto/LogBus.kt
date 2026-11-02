package dev.zanderp.opencfmoto

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide log sink so components running in the foreground service (Android Auto receiver,
 * video pipeline) and in [MainActivity] all funnel into one timestamped buffer that the on-screen
 * log view observes and the Share button exports. Every stage logs here (prefixed `[AA]`,
 * `[VIDEO]`, `[:10922]`, …) per the project's single-log-per-session debugging convention.
 */
object LogBus {
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sb = StringBuilder(64 * 1024)

    /** Receives each already-timestamped line (MainActivity appends it to the TextView). */
    @Volatile var listener: ((String) -> Unit)? = null

    /**
     * When false (default), [LogRedactor] runs on every line before it hits the buffer / UI.
     * Synced from [AppSettings.includeSecretsInLogs] — leave off for Share Logs.
     */
    @Volatile var includeSecrets: Boolean = false

    @Synchronized
    fun log(msg: String) {
        val safe = if (includeSecrets) msg else LogRedactor.redact(msg)
        val line = "${ts.format(Date())}  $safe"
        sb.append(line).append('\n')
        if (sb.length > 512 * 1024) sb.delete(0, sb.length - 256 * 1024)
        // Mirror to logcat so the full diagnostic stream is capturable over adb (`adb logcat -s
        // OpenCfMoto:*`) during on-hardware debugging, not just in the in-app log view.
        Log.i(TAG, safe)
        try { listener?.invoke(line) } catch (_: Exception) {}
    }

    const val TAG = "OpenCfMoto"

    @Synchronized fun snapshot(): String = sb.toString()

    @Synchronized fun clear() { sb.setLength(0) }

    /** Prepend a restored session/crash block (no extra timestamps). */
    @Synchronized
    fun restore(block: String) {
        if (block.isBlank()) return
        val text = if (block.endsWith("\n")) block else "$block\n"
        sb.insert(0, text)
        if (sb.length > 512 * 1024) sb.delete(0, sb.length - 256 * 1024)
    }
}
