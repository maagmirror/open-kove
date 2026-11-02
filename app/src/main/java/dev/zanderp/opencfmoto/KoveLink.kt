// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Keeps a Kove session alive for as long as the rider wants it.
 *
 * On these dashes the phone owns the network, so a lost link cannot be fixed by probing again: the
 * Wi-Fi Direct group may be gone (HyperOS removes it when the phone locks) or the dash may have
 * rebooted and forgotten it. Recovery therefore redoes the whole chain, group → BLE credentials →
 * PXC, while Android Auto keeps running, and never gives up until the rider presses Stop.
 */
object KoveLink {
    private const val STALL_MS = 12_000L
    private const val BOOT_RESTART_AT_MS = 45_000L
    private const val NO_STREAM_MS = 30_000L
    private const val REBUILD_DELAY_MS = 1_500L
    private const val RETRY_STEP_MS = 3_000L
    private const val RETRY_MAX_MS = 15_000L
    private const val WAKE_LOCK_MS = 12 * 60 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var mac: String? = null
    private var link: BleNetLink? = null
    private var receiver: BroadcastReceiver? = null
    private var screenLock: PowerManager.WakeLock? = null

    @Volatile var armed = false
        private set
    @Volatile private var recovering = false
    /** True once the dash has joined, so the group going away afterwards means a lost link. */
    @Volatile private var established = false
    @Volatile private var noStreamSince = 0L
    private var attempts = 0

    fun arm(context: Context, bleMac: String) {
        disarm()
        val ctx = context.applicationContext
        app = ctx
        mac = bleMac
        armed = true
        registerGroupWatch(ctx)
        acquireScreen(ctx)
        LogBus.log("[kove] link supervisor armed")
    }

    /** The BLE link that made the first connection; recovery replaces it. */
    fun attach(current: BleNetLink) {
        link = current
    }

    /** The dash reported its address on our group. */
    fun onDashJoined() {
        established = true
        noStreamSince = 0L
    }

    fun disarm() {
        armed = false
        recovering = false
        established = false
        noStreamSince = 0L
        attempts = 0
        handler.removeCallbacksAndMessages(null)
        try { link?.stop() } catch (_: Exception) {}
        link = null
        val ctx = app
        receiver?.let { r -> try { ctx?.unregisterReceiver(r) } catch (_: Exception) {} }
        receiver = null
        try { if (screenLock?.isHeld == true) screenLock?.release() } catch (_: Exception) {}
        screenLock = null
    }

    /**
     * The dash decides whether to show a session when it starts, and only does so from Connection →
     * WiFi Connection. A session opened moments after power-on beats the rider to that screen, so
     * start one more when the dash has been up long enough for them to reach it.
     */
    fun onDashUptime(uptimeMs: Long) {
        if (!armed || uptimeMs <= 0 || uptimeMs >= BOOT_RESTART_AT_MS) return
        val wait = BOOT_RESTART_AT_MS - uptimeMs
        LogBus.log("[kove] dash just powered on — restarting the session in ${wait / 1000}s")
        handler.postDelayed({ onLinkLost("giving the rider time to open WiFi Connection") }, wait)
    }

    /** Called once per watchdog tick by the foreground service. */
    fun tick() {
        if (!armed || recovering) return
        val prober = BikeLink.prober ?: return
        if (!prober.isRunning) {
            noStreamSince = 0L
            return
        }
        if (prober.isStreaming) {
            noStreamSince = 0L
            if (prober.msSinceLastFrame() > STALL_MS) onLinkLost("no frame pulled for ${STALL_MS / 1000}s")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (noStreamSince == 0L) {
            noStreamSince = now
        } else if (now - noStreamSince > NO_STREAM_MS) {
            onLinkLost("dash did not start streaming within ${NO_STREAM_MS / 1000}s")
        }
    }

    fun onLinkLost(why: String) {
        if (!armed || recovering) return
        recovering = true
        established = false
        noStreamSince = 0L
        attempts = 0
        LogBus.log("[kove] link lost ($why) — rebuilding network and session")
        handler.post { rebuild() }
    }

    private fun rebuild() {
        if (!armed) return
        ConnectionState.set(Phase.RECONNECTING, "")
        try { BikeLink.prober?.stop() } catch (e: Exception) { LogBus.log("[kove] prober stop: $e") }
        try { link?.stop() } catch (_: Exception) {}
        link = null
        PhoneHotspot.stop(LogBus::log)
        handler.postDelayed({ startLink() }, REBUILD_DELAY_MS)
    }

    private fun startLink() {
        val ctx = app ?: return
        val address = mac ?: return
        if (!armed) return
        attempts++
        LogBus.log("[kove] reconnect attempt $attempts")
        lateinit var next: BleNetLink
        next = BleNetLink(
            context = ctx,
            mac = address,
            log = LogBus::log,
            onCarNet = { car, _ ->
                handler.post {
                    if (!armed || link !== next) return@post
                    val bindIp = PhoneHotspot.localAddressFor(car)
                    if (bindIp == null) {
                        LogBus.log("[kove] no local address on the dash's subnet yet")
                        retry()
                        return@post
                    }
                    recovering = false
                    established = true
                    attempts = 0
                    LogBus.log("[kove] dash is back at ${car.hostAddress} — restarting PXC")
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    try {
                        BikeLink.prober?.start(null, gatewayOverride = car, bindIpOverride = bindIp)
                    } catch (e: Exception) {
                        LogBus.log("[kove] prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                handler.post {
                    if (armed && link === next) {
                        LogBus.log("[kove] attempt failed: $reason")
                        retry()
                    }
                }
            },
            onApNeeded = {
                PhoneHotspot.start(
                    context = ctx,
                    log = LogBus::log,
                    onReady = { info -> if (link === next) next.setApInfo(info, info.ip) },
                    onFailed = { reason ->
                        handler.post {
                            if (armed && link === next) {
                                LogBus.log("[kove] group failed: $reason")
                                retry()
                            }
                        }
                    },
                )
            },
        )
        link = next
        next.start()
    }

    private fun retry() {
        if (!armed) return
        try { link?.stop() } catch (_: Exception) {}
        link = null
        PhoneHotspot.stop(LogBus::log)
        val delay = (RETRY_STEP_MS * attempts).coerceAtMost(RETRY_MAX_MS)
        handler.postDelayed({ startLink() }, delay)
    }

    private fun registerGroupWatch(ctx: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                if (established && info?.groupFormed == false) onLinkLost("Wi-Fi Direct group removed")
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            ctx,
            r,
            IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Stops the phone auto-locking mid-ride, which takes the group down. The power button still can. */
    @SuppressLint("WakelockTimeout")
    private fun acquireScreen(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "OpenCfMoto:KoveSession")
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_MS)
            screenLock = lock
        } catch (e: Exception) {
            LogBus.log("[kove] screen wake lock failed: $e")
        }
    }
}
