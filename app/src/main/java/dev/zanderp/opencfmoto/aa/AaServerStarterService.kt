// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
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
 * Starts Android Auto's head unit server from its overflow menu, the only place it can be started.
 *
 * Its accessibility config limits it to the Android Auto package. It only acts while [pending] is
 * set, which [AaReceiver] does when the server is not reachable, and reads nothing else.
 */
class AaServerStarterService : AccessibilityService() {
    private var lastMenuTapMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pending) return
        if (SystemClock.elapsedRealtime() > pendingUntilMs) {
            pending = false
            LogBus.log("[AA] automatic server start timed out")
            return
        }
        val root = rootInActiveWindow ?: return
        val items = ArrayList<AccessibilityNodeInfo>()
        collect(root, items)
        val start = items.firstOrNull { START.containsMatchIn(label(it)) }
        val stop = items.firstOrNull { STOP.containsMatchIn(label(it)) }
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
                val now = SystemClock.elapsedRealtime()
                val menu = items.firstOrNull { MENU.containsMatchIn(label(it)) }
                if (menu != null && now - lastMenuTapMs > MENU_RETRY_MS) {
                    lastMenuTapMs = now
                    clickUp(menu)
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun finish() {
        pending = false
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }, RETURN_DELAY_MS)
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
        private const val MENU_RETRY_MS = 1_500L
        private const val RETURN_DELAY_MS = 700L
        private const val PENDING_MS = 15_000L

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
