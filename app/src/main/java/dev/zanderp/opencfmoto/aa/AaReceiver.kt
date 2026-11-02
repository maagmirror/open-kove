// OpenCfMoto glue (uses AGPLv3 code ported from headunit-revived). Orchestrates the loopback
// "self-mode" Android Auto Projection receiver:
//   1. Listen on TCP 127.0.0.1:5288 (+ NSD _aawireless._tcp).
//   2. Launch Google Android Auto's WirelessStartupActivity pointed at 127.0.0.1:5288 (no VPN).
//   3. Accept the inbound socket, run the AAP version+SSL handshake, point the H.264 decoder at
//      the supplied encoder Surface, and start the message loop → AA video flows into the encoder.
package dev.zanderp.opencfmoto.aa

import android.content.Context
import android.content.Intent
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.BikeWifi
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.NightPrefs
import dev.zanderp.opencfmoto.R
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class AaReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT = 5288
        private const val HEAD_UNIT_SERVER_PORT = 5277
        private const val HEAD_UNIT_RETRY_MS = 2_000L
        private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
        private const val GEARHEAD_SETTINGS_ACTIVITY =
            "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
        private const val HEAD_UNIT_SETTLE_MS = 4_000L
        private const val HEAD_UNIT_HELP_MS = 8_000L
        private const val HEAD_UNIT_AUTO_START_MS = 1_500L
        private const val HEAD_UNIT_WEDGED_AFTER = 2
        private const val HEAD_UNIT_WEDGED_BACKOFF_MS = 10_000L

        /** Process-wide: a restarted receiver must also wait out the previous session's teardown. */
        @Volatile private var lastSessionEndMs = 0L
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var headUnitThread: Thread? = null
    @Volatile private var handshakeFailures = 0
    @Volatile private var shownHint = 0
    @Volatile private var detailBeforeHint: String? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var steadyVideoFired = false

    /**
     * Fired when an AA session ends. [userExit] is true when the rider tapped Exit in Android Auto
     * (VIDEO_FOCUS_NATIVE) — the caller should fully stop projection so the dash doesn't freeze on the
     * last frame; false means a transient drop where the server keeps listening for a reconnect.
     */
    @Volatile var onSessionEnded: ((userExit: Boolean) -> Unit)? = null
    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = ServiceDiscoveryResponse.AA_WIDTH
        fallbackHeight = ServiceDiscoveryResponse.AA_HEIGHT
        onFpsChanged = { fps ->
            log("[AA] decode fps=$fps")
            AaVideoBridge.aaDecoding = true
            if (!steadyVideoFired && fps >= 25) {
                steadyVideoFired = true
                log("[AA] steady video reached (fps=$fps) — signalling ready for bike hand-off")
                try { AaVideoBridge.onSteadyVideo?.invoke() } catch (_: Exception) {}
            }
        }
    }

    /** Ensure Conscrypt/AAP logging are wired before anything touches SSL. */
    fun start() {
        if (running) { log("[AA] already running"); return }
        running = true
        AaLog.sink = log
        ConscryptInitializer.initialize()

        // Loopback listen must not ride a dead bike Network bind (ENONET after Wi‑Fi loss).
        BikeWifi.unbindIfNoBikeNetwork(context)
        try {
            serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
            log("[AA] WirelessServer listening on :$PORT")
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("ENONET", ignoreCase = true) ||
                msg.contains("Network is unreachable", ignoreCase = true)
            ) {
                log("[AA] bind :$PORT hit stale network ($msg) — clearing bind and retrying")
                BikeWifi.unbindProcess(context = context)
                try {
                    serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
                    log("[AA] WirelessServer listening on :$PORT (after unbind)")
                } catch (e2: Exception) {
                    log("[AA] failed to bind :$PORT — ${e2.message}")
                    running = false
                    return
                }
            } else {
                log("[AA] failed to bind :$PORT — $msg")
                running = false
                return
            }
        }

        registerNsd()

        acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        headUnitThread = thread(name = "aa-hu-server", isDaemon = true) { headUnitServerLoop() }
        // Self-mode (launching Google Android Auto) is triggered by MainActivity from the
        // foreground, via AaSelfMode.trigger(), to satisfy background-activity-launch rules.
    }

    /**
     * While this session is alive, re-evaluate the auto day/night value every minute and push it if it
     * changed — so a ride that crosses sunset flips the dash to dark on its own. Only [MapTheme.AUTO]
     * is time-driven; Day/Night are fixed and handled by the initial send + the UI toggle.
     */
    private fun startNightAutoLoop(t: AapTransport) = thread(name = "aa-night", isDaemon = true) {
        try {
            while (transport === t && running) {
                Thread.sleep(60_000)
                if (transport !== t) break
                val want = NightPrefs.isNightNow(context)
                if (want != t.nightMode) {
                    log("[AA] auto map theme → ${if (want) "night" else "day"}")
                    t.sendNightMode(want)
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    fun stop() {
        running = false
        if (transport != null) lastSessionEndMs = SystemClock.elapsedRealtime()
        detailBeforeHint?.let { ConnectionState.set(ConnectionState.phase, it) }
        detailBeforeHint = null
        AaVideoBridge.aaSessionLive = false
        AaVideoBridge.aaDecoding = false
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { transport?.quit() } catch (_: Exception) {}
        transport = null
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        headUnitThread?.interrupt(); headUnitThread = null
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (transport != null) {
                log("[AA] already have a session — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { handleConnection(client) }
        }
    }

    /**
     * Android Auto's developer "head unit server" listens on [HEAD_UNIT_SERVER_PORT] and waits for a
     * head unit to dial in. It needs neither the startup activity (no longer exported on AA 17.9) nor
     * the Bluetooth HFP device the wireless path insists on. AAP is the same either way: the head
     * unit is the TLS client, so only the direction of the TCP connect differs.
     */
    private fun headUnitServerLoop() {
        var announced = false
        val startedAt = SystemClock.elapsedRealtime()
        while (running) {
            if (transport == null) {
                // Dialling straight back while Android Auto is still tearing down the last session
                // leaves its server accepting TCP but never answering VERSION_REQUEST.
                val settle = lastSessionEndMs + HEAD_UNIT_SETTLE_MS - SystemClock.elapsedRealtime()
                if (lastSessionEndMs > 0 && settle > 0) {
                    try { Thread.sleep(settle) } catch (_: InterruptedException) { return }
                    continue
                }
                try {
                    val client = Socket()
                    try {
                        client.connect(InetSocketAddress("127.0.0.1", HEAD_UNIT_SERVER_PORT), 500)
                    } catch (e: Exception) {
                        try { client.close() } catch (_: Exception) {}
                        throw e
                    }
                    log("[AA] connected to Android Auto head unit server on :$HEAD_UNIT_SERVER_PORT")
                    if (running && transport == null) {
                        handleConnection(client)
                    } else {
                        try { client.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    if (!announced) {
                        announced = true
                        log("[AA] head unit server not reachable on :$HEAD_UNIT_SERVER_PORT (${e.message}) — will keep trying")
                    }
                    val auto = AaServerStarterService.isEnabled(context)
                    val waited = SystemClock.elapsedRealtime() - startedAt
                    if (waited > if (auto) HEAD_UNIT_AUTO_START_MS else HEAD_UNIT_HELP_MS) {
                        if (auto) AaServerStarterService.request()
                        showHint(R.string.aa_start_head_unit_server)
                    }
                }
            }
            val wedged = handshakeFailures >= HEAD_UNIT_WEDGED_AFTER
            if (wedged) showHint(R.string.aa_restart_head_unit_server)
            val pause = if (wedged) HEAD_UNIT_WEDGED_BACKOFF_MS else HEAD_UNIT_RETRY_MS
            try { Thread.sleep(pause) } catch (_: InterruptedException) { return }
        }
    }

    /** Tell the rider what to do in Android Auto. Shown once until the state changes. */
    private fun showHint(res: Int) {
        if (shownHint == res || !running) return
        shownHint = res
        val text = context.getString(res)
        log("!! [AA] $text")
        if (detailBeforeHint == null) detailBeforeHint = ConnectionState.detail
        ConnectionState.set(ConnectionState.phase, text)
        Handler(Looper.getMainLooper()).post {
            // Android Auto has no launcher icon, and its server can only be started from this screen.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .setClassName(GEARHEAD_PACKAGE, GEARHEAD_SETTINGS_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                log("[AA] could not open Android Auto settings: ${e.message}")
            }
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearHint() {
        shownHint = 0
        handshakeFailures = 0
        detailBeforeHint?.let { ConnectionState.set(ConnectionState.phase, it) }
        detailBeforeHint = null
    }

    private fun handleConnection(client: Socket) {
        val conn = SocketAccessoryConnection(client)
        connection = conn
        val t = AapTransport(videoDecoder, context)
        t.onQuit = { clean ->
            val userExit = t.wasUserExit
            log("[AA] transport quit (clean=$clean, userExit=$userExit)")
            lastSessionEndMs = SystemClock.elapsedRealtime()
            AaVideoBridge.aaSessionLive = false
            AaVideoBridge.aaDecoding = false
            AaVideoBridge.touchSink = null
            AaVideoBridge.keySink = null
            AaVideoBridge.scrollSink = null
            AaVideoBridge.previewTouchSink = null
            AaVideoBridge.nightSink = null
            try { t.microphone?.stop("transport quit") } catch (_: Exception) {}
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            // Server keeps listening — AA (or the user) can reconnect. On a genuine user Exit we
            // notify the owner so it can fully stop the bike link instead of leaving the dash frozen.
            try { onSessionEnded?.invoke(userExit) } catch (_: Exception) {}
        }
        transport = t
        AaVideoBridge.aaSessionLive = true
        AaVideoBridge.aaDecoding = false
        steadyVideoFired = false

        // Bike touchscreen → Android Auto: EasyConnProber decodes dash touches (PXC cmdType 32) and
        // calls this sink with raw bike-canvas coords + a normalised action. Letterbox-map into AA
        // video space and forward over the AAP INPUT channel. Dropped if the point is in a black bar.
        val input = AaInput(t, log)
        var loggedTouchMap = false
        AaVideoBridge.touchSink = { action, pointerId, cx, cy ->
            val mapped = AaVideoBridge.pipeline?.mapBikeTouchToSource(cx, cy)
            if (mapped != null) {
                if (!loggedTouchMap || action != AaInput.ACTION_MOVE) {
                    log("[AA] touch action=$action p$pointerId bike=($cx,$cy) → AA=(${mapped.first},${mapped.second})")
                    loggedTouchMap = true
                }
                input.sendTouch(action, pointerId, mapped.first, mapped.second)
            }
        }

        // Phone/handlebar D-pad + rotary knob → Android Auto (drives a non-touch dash, and lets the
        // handlebar buttons navigate AA — see MediaButtonBridge). Cleared on transport quit / stop().
        AaVideoBridge.keySink = { keycode -> input.sendKey(keycode) }
        AaVideoBridge.scrollSink = { delta -> input.sendScroll(delta) }

        // In-app HUD preview (HudViewActivity) touches — already in AA source space, sent as-is.
        AaVideoBridge.previewTouchSink = { action, pointerId, sx, sy -> input.sendTouch(action, pointerId, sx, sy) }

        // Map day/night → Android Auto NIGHT sensor (drives Maps' dark/light theme). Seed from the
        // user's Map theme setting, let the UI push live changes, and — in Auto mode — re-evaluate on
        // a timer so the dash follows nightfall mid-ride without any interaction.
        t.nightMode = NightPrefs.isNightNow(context)
        AaVideoBridge.nightSink = { on -> t.sendNightMode(on) }
        startNightAutoLoop(t)

        // Microphone: AA requests it (MICROPHONE_REQUEST) when the Assistant starts; AaMicrophone then
        // streams the phone/helmet mic up the MIC channel so voice destination entry works hands-free.
        t.microphone = AaMicrophone(context, t, log)

        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            log("[AA] handshake FAILED")
            handshakeFailures++
            transport = null
            try { conn.disconnect() } catch (_: Exception) {}
            connection = null
            return
        }
        if (!running) {
            // Stopped while the handshake was in flight; a newer receiver may already own the session.
            log("[AA] handshake finished after stop — closing")
            try { t.quit() } catch (_: Exception) {}
            return
        }
        clearHint()
        log("[AA] handshake OK — pointing decoder at encoder surface and starting read loop")
        videoDecoder.setSurface(encoderSurface)
        t.startReading()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}
