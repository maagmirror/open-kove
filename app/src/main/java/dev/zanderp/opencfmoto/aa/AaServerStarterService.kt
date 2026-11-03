// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Kove. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.aa

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.MainActivity

/**
 * Puts Android Auto in head unit mode without the rider touching its settings.
 *
 * Android Auto only exposes "Start head unit server" in its overflow menu, and that menu only
 * exists once developer settings are on (ten taps on Version). Limited to the Android Auto package
 * by its accessibility config, and idle unless [request] was called.
 */
class AaServerStarterService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastMenuTapMs = 0L
    private var firstSeenMs = 0L
    private var versionTapsAtMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pending) return
        if (SystemClock.elapsedRealtime() > pendingUntilMs) {
            reset()
            LogBus.log("[AA] automatic server start timed out")
            return
        }
        val root = rootInActiveWindow ?: return
        val items = ArrayList<AccessibilityNodeInfo>()
        collect(root, items)
        val start = items.firstOrNull { START.containsMatchIn(label(it)) }
        val stop = items.firstOrNull { STOP.containsMatchIn(label(it)) }
        val now = SystemClock.elapsedRealtime()
        if (firstSeenMs == 0L) firstSeenMs = now
        when {
            start != null && clickUp(start) -> {
                LogBus.log("[AA] started the head unit server from Android Auto's menu")
                finish()
            }
            stop != null -> {
                LogBus.log("[AA] head unit server is already on")
                performGlobalAction(GLOBAL_ACTION_BACK)
                finish()
            }
            else -> {
                val ok = items.firstOrNull { OK.matches(label(it).trim()) }
                val allowDialog = items.any { ALLOW_DEV.containsMatchIn(label(it)) }
                val menu = items.firstOrNull { MENU.containsMatchIn(label(it)) }
                val version = items.firstOrNull { VERSION.containsMatchIn(label(it)) }
                when {
                    allowDialog && ok != null -> {
                        if (clickUp(ok)) LogBus.log("[AA] accepted the developer settings prompt")
                    }
                    menu != null -> {
                        if (now - lastMenuTapMs > MENU_RETRY_MS) {
                            lastMenuTapMs = now
                            clickUp(menu)
                        }
                    }
                    version != null && now - versionTapsAtMs > VERSION_RETRY_MS &&
                        now - firstSeenMs > NO_MENU_GRACE_MS -> {
                        versionTapsAtMs = now
                        LogBus.log("[AA] no menu yet: enabling Android Auto developer settings")
                        tapRepeatedly(version, VERSION_TAPS)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun tapRepeatedly(node: AccessibilityNodeInfo, times: Int) {
        var left = times
        val tap = object : Runnable {
            override fun run() {
                if (!pending || left <= 0) return
                left--
                if (!clickUp(node)) {
                    val fresh = rootInActiveWindow?.let { r ->
                        ArrayList<AccessibilityNodeInfo>().also { collect(r, it) }
                            .firstOrNull { VERSION.containsMatchIn(label(it)) }
                    }
                    if (fresh != null) clickUp(fresh)
                }
                if (left > 0) handler.postDelayed(this, VERSION_TAP_GAP_MS)
            }
        }
        handler.post(tap)
    }

    private fun finish() {
        reset()
        handler.postDelayed({
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }, RETURN_DELAY_MS)
    }

    private fun reset() {
        pending = false
        firstSeenMs = 0L
        versionTapsAtMs = 0L
        lastMenuTapMs = 0L
    }

    private fun label(n: AccessibilityNodeInfo): String = "${n.text ?: ""} ${n.contentDescription ?: ""}"

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (!n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty()) out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
    }

    private fun clickUp(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    companion object {
        private val SERVER = "(head unit|unidad principal|servidor|server)"
        private val START = Regex("(start|iniciar|inicia|comenzar).*$SERVER", RegexOption.IGNORE_CASE)
        private val STOP = Regex("(stop|detener|parar|finalizar).*$SERVER", RegexOption.IGNORE_CASE)
        private val MENU = Regex("^\\s*(más opciones|mas opciones|more options)", RegexOption.IGNORE_CASE)
        private val VERSION = Regex("^\\s*(version|versión)", RegexOption.IGNORE_CASE)
        private val ALLOW_DEV = Regex("(development|developer|desarrollo|desarrollador)", RegexOption.IGNORE_CASE)
        private val OK = Regex("(ok|aceptar|accept|allow|permitir)", RegexOption.IGNORE_CASE)
        private const val MENU_RETRY_MS = 1_500L
        private const val NO_MENU_GRACE_MS = 2_500L
        private const val VERSION_TAPS = 10
        private const val VERSION_TAP_GAP_MS = 120L
        private const val VERSION_RETRY_MS = 15_000L
        private const val RETURN_DELAY_MS = 700L
        private const val PENDING_MS = 40_000L

        @Volatile private var pending = false
        @Volatile private var pendingUntilMs = 0L

        fun request() {
            pendingUntilMs = SystemClock.elapsedRealtime() + PENDING_MS
            pending = true
        }

        fun isEnabled(ctx: Context): Boolean {
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return on.contains(ctx.packageName + "/") && on.contains("AaServerStarterService")
        }
    }
}
