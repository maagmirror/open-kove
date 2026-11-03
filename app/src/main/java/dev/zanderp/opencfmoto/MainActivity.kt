// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: View
    private lateinit var tipsPanel: View
    private lateinit var statusView: TextView
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var statusProgress: View
    private lateinit var bikeView: TextView
    private lateinit var connectBtn: Button
    private lateinit var toggleLogBtn: Button
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    /** Live BLE build-net session (dashes where the phone hosts the network). */
    private var bleNetLink: BleNetLink? = null
    /** Connect attempt parked while the BLE/location permissions are being asked for. */
    private var pendingBleNet: Pair<QrData, Boolean>? = null

    /**
     * Resumes a BLE build-net connect once the rider grants Bluetooth/location. Android Auto is
     * usually already streaming and parked on [BikeLink] by this point, so finishing the handover
     * completes the session without a second Connect tap.
     */
    private val bleNetPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val pending = pendingBleNet
        pendingBleNet = null
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            log(
                "!! denied: ${denied.joinToString { it.substringAfterLast('.') }} — this dash can " +
                    "ONLY be reached over Bluetooth. If Android stopped asking, grant them in " +
                    "Settings ▸ Apps ▸ Open Kove ▸ Permissions, then tap Connect again.",
            )
            ConnectionState.set(Phase.ERROR, "Bluetooth/location permission denied")
            return@registerForActivityResult
        }
        if (pending == null) {
            log("permissions granted — tap Connect")
            return@registerForActivityResult
        }
        log("permissions granted — resuming BLE build-net")
        startBleBuildNet(pending.first, pending.second)
    }
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    /** True when the pending QR scan should kick off the Android Auto flow (vs the mirror path). */
    private var pendingAaStart = false
    /** Guards the "close the official CFMoto app" prompt so it shows once per error, not every redraw. */
    private var rivalPromptShown = false
    /** Guards the VPN kill-switch prompt so it shows once per error, not every redraw. */
    private var vpnPromptShown = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            // A genuine Carbit QR that carries no credentials (Kove: action=128, no ssid/pwd) is
            // NOT a wrong QR — the dash negotiates Wi‑Fi outside the code. Log what it does carry
            // and send the rider to the Wi‑Fi scan instead of calling it invalid.
            val identity = QrData.parseIdentityOnly(raw)
            if (identity != null) {
                log(
                    "QR has no Wi‑Fi credentials — identity only: modelid=${identity.modelId} " +
                        "sn=${identity.sn} action=${identity.action} bm=${identity.bm}",
                )
                // A build-net QR is complete as it stands — it is *meant* to carry no SSID, because
                // the phone hosts the network and hands the credentials over BLE. The vendor client
                // goes straight from `calculationType` → TYPE_NET_BLE → `connectDevice()`; scanning
                // is the whole interaction there. Asking the rider to confirm a dialog and then
                // press Connect was ours, and it made a normal QR look like a failure.
                val built = identity.bm?.takeIf { it.isNotBlank() }
                    ?.let { QrData.buildBleNet(it, identity.modelId, identity.sn) }
                if (built != null) {
                    log("paired BLE build-net bike: ble=${identity.bm} modelId=${identity.modelId}")
                    BikeMemory.save(this, built.first, built.second)
                    refreshBikeLabel()
                    ProjectionHolder.projection = null
                    GpxSession.clear()
                    ensureLocationPermission()
                    startAaFlow(built.second)
                } else {
                    // No `bm` to pair with — there is nothing to connect to, so explain instead.
                    offerWifiScanPairing(identity)
                }
                return@registerForActivityResult
            }
            val hint = QrData.parseFailureHint(raw)
            log("QR parse FAILED — missing ssid/pwd?" + (hint?.let { " ($it)" } ?: ""))
            Toast.makeText(
                this,
                hint ?: getString(R.string.main_invalid_qr),
                Toast.LENGTH_LONG,
            ).show()
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}) modelId=${qr.modelId} sn=${qr.sn}"
        )
        // Remember this bike so the next ride is a one-tap reconnect (skips the scan entirely).
        BikeMemory.save(this, raw, qr)
        refreshBikeLabel()

        if (pendingAaStart) {
            pendingAaStart = false
            startAaFlow(qr)
        } else {
            // Mirror path (screen projection already armed): connect straight away.
            applyProfile(qr)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: qr.ssid)
            joinWifi(qr, gateOnAaSteady = false)
            // Same as startMirrorLink: single-app capture needs the shared app visible.
            moveTaskToBack(true)
            Toast.makeText(
                this,
                getString(R.string.main_mirror_leave_shared_app),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * The scanned QR identifies the dash but publishes no Wi‑Fi credentials — offer the scan-based
     * pairing (pick the SoftAP, type its passphrase once). Keeps the QR's `modelid`/`sn` so profile
     * selection is the same as a credential QR would have given.
     */
    private fun offerWifiScanPairing(identity: QrData.Companion.Identity) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("This dash builds the link over Bluetooth")
            .setMessage(
                "The QR is valid (model ${identity.modelId ?: "?"}) but carries no Wi‑Fi name or " +
                    "password, because this dash has no hotspot of its own: the phone hosts the " +
                    "network and the dash joins it, with the credentials handed over Bluetooth" +
                    (identity.bm?.let { " (dash BLE $it)" } ?: "") + ".\n\n" +
                    "Pairing stores the dash's BLE address; each ride the phone raises a fresh " +
                    "hotspot. Note the phone leaves its own Wi‑Fi while that hotspot is up.",
            )
            .setNeutralButton("Probe Bluetooth") { _, _ -> BtProbe.run(this, identity.bm) }
            .setPositiveButton("Pair over Bluetooth") { _, _ ->
                val bm = identity.bm
                if (bm.isNullOrBlank()) {
                    Toast.makeText(this, "That QR has no BLE address (bm=)", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val built = QrData.buildBleNet(bm, identity.modelId, identity.sn)
                if (built == null) {
                    Toast.makeText(this, "Couldn't build the pairing", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                log("paired BLE build-net bike: ble=$bm modelId=${identity.modelId}")
                BikeMemory.save(this, built.first, built.second)
                refreshBikeLabel()
                Toast.makeText(
                    this,
                    "Added ${BikeMemory.lastBikeName(this)} — tap Connect",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Pick the bike profile from the QR up front — it drives the Android Auto resolution/orientation,
     *  which must be set before AA starts. CLIENT_INFO refines it later during the PXC handshake. */
    private fun applyProfile(qr: QrData) {
        AppSettings.applyToHolder(this)
        BikeProfileHolder.active = BikeProfiles.selectByQr(qr, this)
        DashMemory.setLastDashTouch(this, BikeProfileHolder.advertisesScreenTouch)
        val userOverride = VideoPrefs.resolutionOverride(this, BikeProfileHolder.active)
        // In AUTO mode, if a previous session revealed this dash is a different orientation than the
        // profile assumes, flip AA to match. Learned from the dash's REQ_CONFIG_CAPTURE (see DashMemory).
        val autoGeo = if (userOverride == null) DashMemory.specFor(this, qr.ssid, BikeProfileHolder.active) else null
        BikeProfileHolder.aaVideoOverride = userOverride ?: autoGeo
        val spec = BikeProfileHolder.aaVideo
        BikeProfileHolder.aaContentMargins = VideoPrefs.aaMarginsFor(this, spec, qr.ssid)
        val aspectMode = VideoPrefs.matchAspectMode(this)
        val aspectNote = BikeProfileHolder.aaContentMargins.let { m ->
            when {
                m.any -> " [match-aspect ${aspectMode.name}: margins ${m.marginW}x${m.marginH}]"
                aspectMode == MatchAspectMode.OFF -> " [match-aspect OFF]"
                VideoPrefs.detectedPanelSize(this) == null -> " [match-aspect: waiting for panel size]"
                else -> " [match-aspect ${aspectMode.name}: margins 0]"
            }
        }
        val note = when {
            userOverride != null -> " (override: ${VideoPrefs.resolution(this).label})"
            autoGeo != null -> " (auto-orientation from last connect)"
            else -> ""
        }
        val touchNote = when {
            BikeProfileHolder.forceNonTouch -> " [Disable touchscreen ON — focus/knob AA]"
            BikeProfileHolder.forceTouch -> " [Force touchscreen ON — touch AA]"
            else -> ""
        }
        val ov = BikeProfileHolder.profileOverride
        val ovNote = if (ov != ProfileOverride.AUTO) " [profile override: ${ov.shortLabel}]" else ""
        log("→ bike profile (QR ssid=${qr.ssid} modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
            "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi$note$touchNote$ovNote$aspectNote")
    }

    /**
     * Stop the current dash projection so the next mode can open a clean PXC session.
     * Keeps bike Wi‑Fi (fast re-probe). Optionally preserves an armed Map session / mirror token.
     */
    private fun tearDownForModeSwitch(clearMap: Boolean, clearMirror: Boolean) {
        log("→ mode switch: stop previous projection (keep Wi‑Fi)")
        KoveLink.disarm()
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        AaVideoBridge.pipeline = null
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { AndroidAutoService.stop(this) } catch (e: Exception) { log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { log("prober stop: $e") }
        try {
            if (::prober.isInitialized && BikeLink.prober !== prober) prober.stop()
        } catch (_: Exception) {}
        if (clearMirror) {
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            try { ProjectionService.stop(this) } catch (_: Exception) {}
        }
        if (clearMap) {
            try { GpxSession.clear() } catch (_: Exception) {}
            MapInputBridge.clear()
        }
        BikeLink.beginHandoff()
    }

    /** Start the Android Auto → bike projection for [qr]. Shared by the one-tap Connect reconnect
     *  and a fresh scan, so both paths behave identically. */
    private fun startAaFlow(qr: QrData) {
        if (!BikeLink.claimFlowStart()) {
            log("→ ignoring duplicate Android Auto start (one began moments ago)")
            return
        }
        try {
            if (!WifiGate.ensureEnabledOrPrompt(this)) return
            // Map / Mirror / stale PXC must die first — half-switches leave framesSent=0.
            tearDownForModeSwitch(clearMap = true, clearMirror = true)
            applyProfile(qr)
            val bikeName = BikeMemory.lastBikeName(this) ?: qr.ssid
            ConnectionState.set(Phase.STARTING_AA, bikeName)
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")

            // Parallel startup: the two slow steps (AA reaching steady video, and the user accepting the
            // bike Wi-Fi dialog) now overlap. [BikeLink] gates the actual bike probe until BOTH complete,
            // so the bike is never contacted before AA has frames to serve. These callbacks fire against
            // process-global state (applicationContext + BikeLink.prober), NOT this activity: launching
            // Google AA can destroy/recreate MainActivity mid-startup and the hand-off must still finish.
            BikeLink.beginHandoff()
            AaVideoBridge.onSteadyVideo = {
                AaVideoBridge.onSteadyVideo = null
                ConnectionState.set(Phase.AA_VIDEO_LIVE)
                LogBus.log("→ Android Auto video is live")
                BikeLink.markAaVideoSteady()
            }
            AndroidAutoService.start(this)
            // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
            // safe on Android 12+/15), after giving the service's :5288 server time to bind.
            // AA 16.4+/17.4+ often need the broadcast fallbacks; retry once if video never arrives
            // (common when the first WirelessStartupReceiver is ignored).
            logView.postDelayed({
                try {
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
                } catch (e: Exception) {
                    log("AA self-mode trigger failed: $e")
                }
            }, 900)
            // Additive retry for AA 16.4+/17.4+ that ignore the first broadcast — skipped if
            // a session is already live (re-firing START_WIRELESS_PROJECTION kills AAP).
            logView.postDelayed({
                val p = ConnectionState.phase
                if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding ||
                    p == Phase.AA_VIDEO_LIVE || p == Phase.PXC_CONNECTING || p == Phase.STREAMING ||
                    p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
                    p == Phase.MIRRORING
                ) {
                    if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) {
                        log("[AA] skip re-trigger — AA already connected/decoding")
                    }
                    return@postDelayed
                }
                try {
                    log("[AA] no video yet — re-triggering self-mode (AA 16.4+/17.4+ fallbacks)")
                    dev.zanderp.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
                } catch (e: Exception) {
                    log("AA self-mode re-trigger failed: $e")
                }
            }, 4_500)
            // Kick off the Wi-Fi join right away, in parallel with AA boot.
            joinWifi(qr, gateOnAaSteady = true)
        } catch (e: Exception) {
            log("Connect failed (app stays open): $e")
            CrashGuard.persistSession(this)
            ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_connect_failed))
        }
    }

    /** After a fatal crash, tell the rider logs survived and keep Share Logs useful. */
    private fun maybeShowCrashRecovery() {
        if (CrashGuard.pendingCrashText(this) == null) return
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.main_crash_title)
                .setMessage(
                    "Open Kove closed unexpectedly last time. The log and crash report were saved — " +
                        "open Logs and tap Share Logs so we can see what happened.",
                )
                .setPositiveButton("Share Logs") { _, _ -> shareLog() }
                .setNegativeButton("Keep logs") { _, _ -> }
                .setNeutralButton("Dismiss") { _, _ -> CrashGuard.clearCrash(this) }
                .show()
        } catch (e: Exception) {
            log("crash recovery UI failed: $e")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms)
        // instead of guessing a fixed delay.
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val maxTries = 50  // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        GpxSession.clear()
                        log("screen-capture armed (FGS up after ${tries * 100}ms) — pick Entire screen or a single app (Android 14+)")
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.main_mirror_ready_title)
                            .setMessage(
                                "Entire screen — best for riding; phone stays awake while mirroring. " +
                                    "Uses Setup ▸ Screen fit (Fit = whole UI + bars).\n\n" +
                                    "Single app — that app must stay on screen; Android sends no frames " +
                                    "in the background. Prefer GPX / Tracks or Android Auto for pocket use.\n\n" +
                                    "Bike touch does not drive mirrored apps. Continue connects and " +
                                    "sends Open Kove to the background.",
                            )
                            .setPositiveButton("Continue") { _, _ -> startMirrorLink() }
                            .setCancelable(false)
                            .show()
                    } catch (e: Exception) {
                        log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@MainActivity)
                    }
                } else if (tries++ < maxTries) {
                    logView.postDelayed(this, 100)
                } else {
                    log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@MainActivity)
                }
            }
        }
        logView.post(poll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.applyToHolder(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logPanel = findViewById(R.id.log_panel)
        tipsPanel = findViewById(R.id.tips_panel)
        statusView = findViewById(R.id.status_view)
        statusIcon = findViewById(R.id.status_icon)
        statusProgress = findViewById(R.id.status_progress)
        bikeView = findViewById(R.id.bike_view)
        connectBtn = findViewById(R.id.btn_connect)
        toggleLogBtn = findViewById(R.id.btn_toggle_log)
        logView.movementMethod = ScrollingMovementMethod()

        // Icons are set here rather than in XML: in this AGP/compileSdk setup, library (res-auto)
        // attributes like app:icon don't resolve in layouts, so we assign them programmatically.
        (connectBtn as? MaterialButton)?.setIconResource(R.drawable.ic_power)
        findViewById<android.widget.TextView>(R.id.brand_version).text =
            "v${BuildConfig.VERSION_NAME}"
        // Scan / Map / Mirror: icon on top (Tile style). maxLines=1 + autoSize keeps labels
        // horizontal on narrow phones / large system font (Samsung S22 report).
        fun MaterialButton.asIconTopTile(iconRes: Int) {
            setIconResource(iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            iconPadding = resources.getDimensionPixelSize(R.dimen.btn_tile_icon_padding)
            maxLines = 1
            isSingleLine = true
        }
        (findViewById<View>(R.id.btn_aa_start) as? MaterialButton)?.asIconTopTile(R.drawable.ic_qr)
        (findViewById<View>(R.id.btn_gpx) as? MaterialButton)?.asIconTopTile(R.drawable.ic_place)
        (findViewById<View>(R.id.btn_mirror_start) as? MaterialButton)?.asIconTopTile(R.drawable.ic_cast)
        (findViewById<View>(R.id.btn_aa_stop) as? MaterialButton)?.setIconResource(R.drawable.ic_stop)
        (findViewById<View>(R.id.btn_hud_view) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_cast)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            maxLines = 1
        }
        (findViewById<View>(R.id.btn_controls) as? MaterialButton)?.apply {
            setIconResource(R.drawable.ic_devices)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            maxLines = 1
        }
        // Footer 2×2 — icon + label (same pattern as Dash view / Controls).
        fun MaterialButton.asFooterLink(iconRes: Int) {
            setIconResource(iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = (6 * resources.displayMetrics.density).toInt()
            iconSize = (18 * resources.displayMetrics.density).toInt()
            setIconTintResource(R.color.text_secondary)
            maxLines = 1
            isSingleLine = true
        }
        (findViewById<View>(R.id.btn_setup) as? MaterialButton)?.asIconTopTile(R.drawable.ic_settings)
        (findViewById<View>(R.id.btn_devices) as? MaterialButton)?.asFooterLink(R.drawable.ic_devices)
        (findViewById<View>(R.id.btn_trip) as? MaterialButton)?.asFooterLink(R.drawable.ic_ride)
        (toggleLogBtn as? MaterialButton)?.asIconTopTile(R.drawable.ic_logs)

        // All components (bike PXC, Android Auto receiver, video pipeline — including those
        // running in the foreground service) log through LogBus; mirror it into the view.
        // Android's ContentCapture service tries to parcel the whole TextView text on every change;
        // a long session grows the log to megabytes → TransactionTooLargeException → the Activity
        // crashes (looks like "the app minimises itself and disconnects"). Opt the log view out of
        // ContentCapture and cap its on-screen length so it can never grow without bound.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            logView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
        }
        LogBus.listener = { line ->
            runOnUiThread {
                if (logView.length() > LOG_VIEW_MAX_CHARS) {
                    val keep = logView.text.toString().takeLast(LOG_VIEW_MAX_CHARS / 2)
                    logView.text = keep
                }
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        // Application already hydrated a prior session/crash into LogBus — show it in the view.
        val prior = LogBus.snapshot()
        if (prior.isNotBlank()) {
            logView.text = prior
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        maybeShowCrashRecovery()
        // Soft dep check after auto-connect has had time to start — never race Connect.
        logView.postDelayed({ DependencyPrompt.showOnLaunchIfNeeded(this) }, 2500)
        logView.postDelayed({
            UpdateUi.checkAuto(this) { release ->
                refreshUpdateBadge()
                if (!ConnectionState.phase.busy && ConnectionState.phase != Phase.STREAMING) UpdateUi.show(this, release)
            }
        }, 4000)
        // Opening the home screen must not resume a killed NAV_TO (looks like "Arrived at X").
        val startingGpx = intent?.getBooleanExtra(EXTRA_START_GPX, false) == true
        val projecting = ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING
        if (!startingGpx && !projecting) {
            GpxSession.abandonStaleNavigation("app open")
        }
        if (startingGpx) {
            intent.removeExtra(EXTRA_START_GPX)
            logView.post { beginGpxProjection() }
        }

        // Reflect the coarse connection state in the big status header (so users don't read the log).
        ConnectionState.listener = { phase, detail ->
            runOnUiThread { renderStatus(phase, detail) }
        }
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        refreshBikeLabel()

        // Reuse the process-global prober if one already exists (e.g. this activity was recreated
        // while the Android Auto receiver kept running in the foreground service). Constructing a
        // fresh one here would orphan the running instance — leaking its sockets/threads and making
        // the Stop button operate on the wrong object. See [BikeLink].
        prober = BikeLink.prober ?: EasyConnProber(applicationContext, LogBus::log).also { BikeLink.prober = it }

        // Android 13+: request notification permission up front so the mediaProjection
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // Connect while idle; Stop while busy / projecting (merged former Stop button).
        connectBtn.setOnClickListener {
            val phase = ConnectionState.phase
            if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
                stopEverything()
                return@setOnClickListener
            }
            if (DependencyPrompt.showForConnect(this, forScan = false)) {
                log("→ Connect blocked — missing dependencies (see dialog)")
                return@setOnClickListener
            }
            val saved = BikeMemory.lastQr(this)
            if (saved != null) {
                log("→ Connect: reusing saved bike '${BikeMemory.lastBikeName(this)}' (no scan needed)")
                ProjectionHolder.projection = null
                GpxSession.clear()
                ensureLocationPermission()
                startAaFlow(saved)
            } else {
                log("→ Connect: no saved bike — scan the dash QR.")
                startAaScan()
            }
        }

        findViewById<Button>(R.id.btn_aa_start).setOnClickListener { startAaScan() }

        findViewById<Button>(R.id.btn_mirror_start).setOnClickListener { onMirrorPressed() }
        findViewById<View>(R.id.btn_gpx).setOnClickListener { onMapPressed() }
        findViewById<Button>(R.id.btn_aa_stop).setOnClickListener { stopEverything() }

        toggleLogBtn.setOnClickListener {
            val show = logPanel.visibility != View.VISIBLE
            logPanel.visibility = if (show) View.VISIBLE else View.GONE
            // The tips panel and the log panel share the flexible space, so only one shows at a time.
            tipsPanel.visibility = if (show) View.GONE else View.VISIBLE
            toggleLogBtn.text = if (show) getString(R.string.main_hide_logs) else getString(R.string.main_logs)
        }

        findViewById<View>(R.id.btn_hud_view).setOnClickListener { startActivity(Intent(this, HudViewActivity::class.java)) }
        findViewById<View>(R.id.btn_controls).setOnClickListener { startActivity(Intent(this, ControlsActivity::class.java)) }
        findViewById<Button>(R.id.btn_navigate).setOnClickListener { navigateToTyped() }
        val destField = findViewById<android.widget.EditText>(R.id.et_destination)
        destField?.setOnEditorActionListener { _, _, _ ->
            navigateToTyped(); true
        }
        // Bike Search tap → focus this field + show the phone keyboard (Presentation has no IME).
        DashRemote.setTypeOnPhoneHandler {
            runOnUiThread {
                val field = findViewById<android.widget.EditText>(R.id.et_destination)
                    ?: return@runOnUiThread
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                            ),
                    )
                } catch (_: Exception) {}
                // Make the phone field scream "this is the bike search box".
                field.hint = getString(R.string.main_bike_search_hint)
                field.setText("")
                field.setSelection(0)
                try {
                    field.setBackgroundColor(0x33FF9800)
                    field.postDelayed({
                        try { field.background = null } catch (_: Exception) {}
                    }, 2500)
                } catch (_: Exception) {}
                field.requestFocus()
                field.post {
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(
                        field,
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                    )
                }
                try {
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                        ?.vibrate(40)
                } catch (_: Exception) {}
                Toast.makeText(
                    this,
                    "Bike search open — type here. Results appear on the dash.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        // Live suggestions on the dash while typing on the phone.
        destField?.addTextChangedListener(object : android.text.TextWatcher {
            private val debounce = android.os.Handler(android.os.Looper.getMainLooper())
            private val run = Runnable {
                val q = destField.text?.toString()?.trim().orEmpty()
                // Live suggestions only while OUR map is on the bike — not while Android Auto is live.
                if (q.length >= 2 && GpxSession.active && DashRemote.isAvailable) DashRemote.submit(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                debounce.removeCallbacks(run)
                debounce.postDelayed(run, 350)
            }
        })
        findViewById<View>(R.id.btn_devices).setOnClickListener { GarageActivity.start(this) }

        findViewById<View>(R.id.btn_trip).setOnClickListener { TripActivity.start(this) }

        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        findViewById<Button>(R.id.btn_setup).setOnClickListener { SetupActivity.start(this) }
        findViewById<View>(R.id.btn_todo).setOnClickListener { OnboardingActivity.start(this) }
        findViewById<View>(R.id.brand_title).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.btn_about_page).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.btn_check_update).setOnClickListener { checkUpdateManual() }
        findViewById<View>(R.id.btn_problem_report).setOnClickListener { reportProblem() }
        findViewById<View>(R.id.btn_donate).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.URL_KOFI)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.main_donate_failed, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            logView.text = ""
        }

        log("Ready. Tap Connect to project Android Auto to your dash.")

        // First launch: walk the user through the one-time prerequisites.
        try {
            LegacyInstall.maybeWarn(this)
            if (!SetupActivity.hasSeen(this)) OnboardingActivity.start(this)
            else maybeAutoConnect()
            maybeResumeFromParked(intent)
        } catch (e: Exception) {
            log("startup failed (UI still up): $e")
            CrashGuard.persistSession(this)
        }
    }

    override fun onPause() {
        CrashGuard.persistSession(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeResumeFromParked(intent)
        if (intent.getBooleanExtra(EXTRA_START_GPX, false)) {
            intent.removeExtra(EXTRA_START_GPX)
            beginGpxProjection()
        }
        if (intent.getBooleanExtra(EXTRA_START_SCAN, false)) {
            intent.removeExtra(EXTRA_START_SCAN)
            startAaScan()
        }
    }

    /** Shows what is still missing for a first connection; hidden once everything is in place. */
    private fun refreshTodo() {
        val n = OnboardingActivity.missingSteps(this)
        val panel = findViewById<View>(R.id.todo_panel)
        panel.visibility = if (n == 0) View.GONE else View.VISIBLE
        if (n == 0) return
        val lines = buildList {
            if (!SetupHelper.isAndroidAutoInstalled(this@MainActivity)) add(getString(R.string.onboarding_aa_title))
            if (!SetupHelper.permissionsGranted(this@MainActivity)) add(getString(R.string.onboarding_perms_title))
            if (BuildConfig.AUTO_SERVER_START &&
                !dev.zanderp.opencfmoto.aa.AaServerStarterService.isEnabled(this@MainActivity)
            ) {
                add(getString(R.string.onboarding_auto_title))
            }
            if (!BikeMemory.hasSaved(this@MainActivity)) add(getString(R.string.onboarding_bike_title))
        }
        findViewById<TextView>(R.id.todo_body).text = lines.joinToString(System.lineSeparator()) { "• $it" }
    }

    override fun onResume() {
        super.onResume()
        // The active bike (and its name) may have changed in the Garage — reflect it on the label and
        // the Connect button.
        refreshBikeLabel()
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        refreshTodo()
        refreshUpdateBadge()
        if (WifiGate.isWifiEnabled(this)) WifiGate.cancelNotification(this)
        // Retry auto-connect on resume: after finishing first-run setup, or once the bike's Wi-Fi
        // comes into range shortly after launch. Guarded so it only ever starts one attempt.
        if (SetupActivity.hasSeen(this)) maybeAutoConnect()
        maybeResumeFromParked(intent)
    }

    /**
     * Guaranteed, BAL-safe resume after the service parked Android Auto (long bike outage). Re-launching
     * Google AA needs a foreground Activity, so when the rider taps the "Bike reconnected" notification
     * (or just opens the app while parked and the bike looks in range) we finish the resume here — this
     * `startActivity(gearhead)` is allowed because we're in the foreground.
     */
    private fun maybeResumeFromParked(intent: Intent?) {
        val explicit = intent?.getBooleanExtra(AndroidAutoService.EXTRA_RESUME, false) == true
        if (!explicit && !AndroidAutoService.isParked && ConnectionState.phase != Phase.WAITING_FOR_BIKE) return
        val saved = BikeMemory.lastQr(this) ?: return
        // On a plain open (not an explicit tap), only resume when the bike doesn't look clearly absent.
        if (!explicit && BikeWifi.isSsidInRange(this, saved.ssid) == false) return
        intent?.removeExtra(AndroidAutoService.EXTRA_RESUME)
        log("→ Resuming projection to '${BikeMemory.lastBikeName(this)}' from the foreground")
        autoConnectStarted = true
        AndroidAutoService.notifyForegroundResuming()
        ProjectionHolder.projection = null
        ensureLocationPermission()
        startAaFlow(saved)
    }

    /**
     * Auto-connect on launch: if the rider left the feature on, a bike is paired, Android Auto is
     * installed, nothing is already running, and the bike's Wi-Fi looks in range, kick off the same
     * one-tap Connect flow automatically. Fires at most once per process ([autoConnectStarted]) — but
     * only that success latches it, so a "not in range yet" launch is retried from [onResume] once the
     * bike's Wi-Fi appears. Never interrupts an active session or the setup wizard.
     */
    private fun maybeAutoConnect() {
        if (autoConnectStarted) return
        if (!AppSettings.autoConnect(this)) return
        if (AndroidAutoService.isRunning) return
        if (ConnectionState.phase.busy ||
            ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING) return
        val saved = BikeMemory.lastQr(this)
        if (saved == null) {
            logAutoConnectSkipOnce("no bike paired")
            return
        }
        if (!SetupHelper.isAndroidAutoInstalled(this)) {
            logAutoConnectSkipOnce("Android Auto not installed")
            return
        }
        if (!WifiGate.isWifiEnabled(this)) {
            // Don't latch autoConnectStarted — retry once the rider turns Wi‑Fi back on.
            WifiGate.ensureEnabledOrPrompt(this)
            logAutoConnectSkipOnce("phone Wi‑Fi is off — turn it on to connect")
            return
        }

        val inRange = BikeWifi.isSsidInRange(this, saved.ssid)
        if (inRange == false) {
            logAutoConnectSkipOnce("'${BikeMemory.lastBikeName(this)}' not in range — will retry when its Wi-Fi appears")
            return
        }

        // Committed to connecting — latch so an activity recreation (Google AA foregrounding) or a
        // later onResume doesn't fire a second attempt. Use the main looper (not the view) so a
        // dependency dialog can't cancel the delayed start with the view.
        autoConnectStarted = true
        val why = if (inRange == true) "Wi-Fi in range" else "range unknown — trying anyway"
        log("→ Auto-connect: '${BikeMemory.lastBikeName(this)}' ($why). Disable in Setup ▸ Startup.")
        ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
        ensureLocationPermission()
        val activity = this
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    // Recreated before we fired — let the new instance retry.
                    autoConnectStarted = false
                    return@postDelayed
                }
                if (!AndroidAutoService.isRunning && !ConnectionState.phase.busy) {
                    activity.startAaFlow(saved)
                }
            } catch (e: Exception) {
                LogBus.log("auto-connect failed (app stays open): $e")
                CrashGuard.persistSession(activity)
                ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_auto_connect_failed))
            }
        }, 1200)
    }

    /** Log an auto-connect skip reason at most once per process, so onResume retries don't spam. */
    private fun logAutoConnectSkipOnce(reason: String) {
        if (autoConnectSkipLogged) return
        autoConnectSkipLogged = true
        log("→ Auto-connect idle: $reason.")
    }

    override fun onDestroy() {
        // The BLE build-net hotspot survives Stop→Connect on purpose (see stopEverything), so this
        // is where it finally goes: the rider is leaving the app, nothing is projecting.
        if (isFinishing) {
            try { PhoneHotspot.stop(::log) } catch (_: Exception) {}
        }
        DashRemote.setTypeOnPhoneHandler(null)
        LogBus.listener = null
        ConnectionState.listener = null
        // When the Android Auto receiver service is running, the whole AA→bike chain (receiver +
        // encoder in the FGS, plus the Wi-Fi + prober in process globals) must OUTLIVE this activity:
        // launching Google Android Auto can destroy/recreate MainActivity mid-hand-off, and tearing
        // the bike down here is exactly what left the dash on a black screen (the pending
        // onSteadyVideo hand-off was cancelled before it could fire). Only tear down when AA is NOT
        // running — i.e. the mirror path or a genuine exit. Full teardown is the "Stop" button.
        if (!AndroidAutoService.isRunning) {
            AaVideoBridge.onSteadyVideo = null
            prober.stop()
            bleWakeUp?.stop()
            bleWakeUp = null
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            ProjectionService.stop(this)
            // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
            // meant to keep running when the phone is backgrounded/locked. Use "Stop Android Auto".
            BikeWifi.leave(this, ::log)
            BikeWifiP2p.stop(::log)
        }
        super.onDestroy()
    }

    /** Launch the QR scanner for the Android Auto path (profile is chosen from the scan result). */
    private fun startAaScan() {
        if (DependencyPrompt.showForConnect(this, forScan = true)) {
            log("→ Scan blocked — missing dependencies (see dialog)")
            return
        }
        log("→ Android Auto: scan the bike QR first so we pick the right screen profile.")
        pendingAaStart = true
        ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
        ensureLocationPermission()
        try {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        } catch (e: Exception) {
            log("scan launch failed ($e)")
            pendingAaStart = false
        }
    }

    /** Update the big status header + Connect button label from a [ConnectionState] transition. */
    private fun renderStatus(phase: Phase, detail: String) {
        statusView.text = getString(phase.labelRes)
        val base = if (detail.isNotBlank()) detail else bikeLabelText()
        // A Kove only shows the projection if it is on this screen when the session starts.
        bikeView.text = if (phase.busy || phase == Phase.STREAMING) {
            base + System.lineSeparator() + getString(R.string.main_kove_screen_hint)
        } else {
            base
        }
        val color = when (phase) {
            Phase.STREAMING, Phase.MIRRORING -> ContextCompat.getColor(this, R.color.status_live)
            Phase.ERROR -> ContextCompat.getColor(this, R.color.status_error)
            else -> if (phase.busy) ContextCompat.getColor(this, R.color.status_busy)
                    else ContextCompat.getColor(this, R.color.status_idle)
        }
        statusView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        statusIcon.setColorFilter(color)
        statusProgress.visibility = if (phase.busy) View.VISIBLE else View.GONE

        // The bike's link ports are held by the official CFMoto app — offer to close it (see
        // EasyConnProber's bind-conflict path). Show once per error so we don't nag on every redraw.
        if (phase == Phase.ERROR && detail.contains("official", ignoreCase = true)) {
            if (!rivalPromptShown) { rivalPromptShown = true; promptCloseRival() }
        } else {
            rivalPromptShown = false
        }
        // Only a confirmed kill-switch with a live internet VPN — Map↔AA blips can leave a stale
        // "kill-switch" detail without an actual VPN; don't nag in that case.
        if (phase == Phase.ERROR &&
            detail.contains("kill-switch", ignoreCase = true) &&
            BikeWifi.isVpnActive(this)
        ) {
            if (!vpnPromptShown) { vpnPromptShown = true; promptVpnKillSwitch() }
        } else {
            vpnPromptShown = false
        }
        connectBtn.text = when {
            phase.busy -> getString(R.string.main_stop)
            phase == Phase.STREAMING || phase == Phase.MIRRORING -> getString(R.string.main_stop)
            BikeMemory.hasSaved(this) ->
                getString(R.string.main_connect_to, BikeMemory.lastBikeName(this) ?: "")
            else -> getString(R.string.main_connect)
        }
        (connectBtn as? MaterialButton)?.setIconResource(
            if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
                R.drawable.ic_stop
            } else {
                R.drawable.ic_power
            },
        )
        updateButtonStates(phase)
    }

    /** Enable actions that make sense in the current [phase]. */
    private fun updateButtonStates(phase: Phase) {
        val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
        val busy = phase.busy
        // Connect/Stop always tappable except pure idle race — Stop while busy/live, Connect otherwise.
        connectBtn.isEnabled = true
        // Scan only when not mid-flight.
        setEnabled(R.id.btn_aa_start, !busy)
        // Map / Mirror stay available while live so the rider can switch dash content.
        setEnabled(R.id.btn_mirror_start, !busy || live)
        setEnabled(R.id.btn_gpx, !busy || live)
        setEnabled(R.id.btn_aa_stop, busy || live)
    }

    private fun setEnabled(id: Int, enabled: Boolean) {
        findViewById<View>(id)?.let {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    /** Show which bike (if any) is remembered, under the status header. */
    private fun refreshBikeLabel() {
        bikeView.text = bikeLabelText()
    }

    /**
     * The official CFMoto app is holding the mirroring-link ports. Android won't let us silently
     * force-stop another app, so offer a best-effort background kill and a one-tap jump to its
     * App-info screen (guaranteed Force stop), then reconnect.
     */
    private fun promptCloseRival() {
        val installed = RivalClient.isInstalled(this)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_rival_title)
            .setMessage(
                "The official CFMoto/EasyConnect app is running and holding the bike's link ports, so " +
                    "the dash stays blank. Close it, then reconnect.\n\n" +
                    "\"Close & retry\" tries to stop it for you; if the dash is still blank, use " +
                    "\"App settings\" and tap Force stop."
            )
            .setPositiveButton("Close & retry") { _, _ ->
                val killed = RivalClient.closeBestEffort(this)
                log(if (killed) "→ asked Android to close the official CFMoto app; retrying…"
                    else "→ couldn't auto-close the official CFMoto app — open its settings to Force stop.")
                connectBtn.postDelayed({ reconnectSavedBike() }, 1500)
            }
            .setNeutralButton("App settings") { _, _ ->
                if (!RivalClient.openAppInfo(this)) {
                    Toast.makeText(this, R.string.main_settings_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Dismiss", null)
            .setCancelable(true)
            .apply { if (!installed) setMessage("Something is holding the bike's link ports (10920-10922). Close any other CFMoto/EasyConnect app and reconnect.") }
            .show()
    }

    /**
     * Always-on VPN with connection blocking prevents pinning sockets to bike Wi-Fi (EPERM).
     * Offer a shortcut into system VPN settings; the user must disable the kill-switch or VPN.
     */
    private fun promptVpnKillSwitch() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("VPN is blocking the bike")
            .setMessage(
                "Android Auto reached the phone, but a VPN kill-switch is blocking the bike Wi‑Fi " +
                    "(Network.bindSocket → EPERM).\n\n" +
                    "Fix (any one):\n" +
                    "• Turn the VPN off for this ride\n" +
                    "• Disable Always-on VPN → \"Block connections without VPN\"\n" +
                    "• Allow LAN / local network in the VPN app (PCAPdroid, AdGuard, etc.)\n\n" +
                    "Then tap Connect again."
            )
            .setPositiveButton("VPN settings") { _, _ ->
                try {
                    startActivity(Intent("android.net.vpn.SETTINGS"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Open Settings ▸ Network ▸ VPN", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Dismiss", null)
            .setCancelable(true)
            .show()
    }

    /** Re-run the one-tap Connect for the saved bike (used after closing the rival app). */
    private fun reconnectSavedBike() {
        val saved = BikeMemory.lastQr(this) ?: return
        ProjectionHolder.projection = null
        ensureLocationPermission()
        startAaFlow(saved)
    }

    private fun bikeLabelText(): String {
        val name = BikeMemory.lastBikeName(this)
        return if (name != null) getString(R.string.main_paired, name)
        else getString(R.string.main_no_bike_paired)
    }

    /**
     * Join the bike Wi-Fi and start the PXC prober.
     *
     * When [gateOnAaSteady] is true (Android Auto path), the prober start is handed to [BikeLink],
     * which waits until AA video is also steady — so this Wi-Fi join can run in parallel with AA
     * boot. When false (mirror path), the prober starts as soon as Wi-Fi is bound.
     *
     * Uses applicationContext + the process-global prober (not this activity) so the hand-off
     * completes even if the activity is destroyed/recreated after it was armed.
     */
    private fun joinWifi(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        ConnectionState.set(Phase.JOINING_WIFI)
        // Dashes with no hotspot of their own: we host, they join. Not a transport preference —
        // it's the only way in for these bikes, so the QR decides, not Setup.
        if (!qr.bleMac.isNullOrBlank()) {
            startBleBuildNet(qr, gateOnAaSteady)
            return
        }
        // Every other dash is a CFMoto one — keep the identity they expect, and keep hardware
        // encoding (cheaper, and they render it fine).
        EasyConnProber.useCfmotoIdentity()
        VideoPipeline.preferSoftwareEncoder = false
        val transport = AppSettings.transport(this)
        if (transport == WifiTransport.EXISTING) {
            useExistingWifi(gateOnAaSteady)
            return
        }
        // AUTO: only use Wi‑Fi Direct when the QR SSID is a real P2P group name (DIRECT-…).
        // Some bikes (e.g. QJ Motor) set action=P2P-only but advertise a normal SoftAP name
        // like "qj-5G-…"; P2P credential-join rejects those and we waste 25s before AP fallback.
        val useP2p = when (transport) {
            WifiTransport.P2P -> true
            WifiTransport.AP -> false
            WifiTransport.AUTO -> qr.ssid.startsWith("DIRECT-", ignoreCase = true) &&
                (qr.supportsP2p || !qr.supportsAp)
            // Handled above by the early return — listed so the compiler keeps this exhaustive.
            WifiTransport.EXISTING -> false
        }
        if (useP2p) {
            joinWifiP2p(qr, gateOnAaSteady)
            return
        }
        if (transport == WifiTransport.AUTO && qr.supportsP2p && !qr.supportsAp &&
            !qr.ssid.startsWith("DIRECT-", ignoreCase = true)
        ) {
            log(
                "→ QR says P2P-only but SSID '${qr.ssid}' is not DIRECT-* — " +
                    "joining as SoftAP (Setup → P2P if this bike is truly Wi‑Fi Direct)",
            )
        }
        BikeWifi.reuseOrJoin(
            context = applicationContext,
            ssid = qr.ssid,
            psk = qr.pwd,
            hidden = qr.hidden,
            wpa3 = qr.isSae,
            onAvailable = { network ->
                WifiGate.cancelNotification(applicationContext)
                if (gateOnAaSteady) {
                    LogBus.log("→ bike Wi-Fi bound (waiting for AA video to go steady)")
                    BikeLink.markWifiReady(network)
                } else {
                    // Map / Mirror: no AA gating — rebuild PXC immediately on the bound network.
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(network ?: BikeWifi.currentNetwork)
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onLost = { LogBus.log("bike network lost") },
            log = LogBus::log,
        )
    }

    /**
     * [WifiTransport.EXISTING]: join nothing, run PXC over the network the phone is already on.
     *
     * For dashes whose hotspot only exists while their own app holds it up, with credentials that
     * change every session — there is nothing to store and nothing to type. The rider connects with
     * that app first; we ride the association it made.
     *
     * Whether that actually works depends on how the other app joined: a `WifiNetworkSpecifier`
     * join stays private to it and never becomes our default route. [NetDiag] dumps enough to tell
     * which case we're in, and the prober's own gateway resolution ([EasyConnProber.start] with a
     * null network → `activeNetwork`) does the rest.
     */
    private fun useExistingWifi(gateOnAaSteady: Boolean) {
        LogBus.log("→ transport=EXISTING — not joining any Wi‑Fi; using the phone's current network")
        BikeWifi.unbindProcess(context = applicationContext)
        NetDiag.dump(applicationContext) { LogBus.log(it) }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val ssid = try { wm?.connectionInfo?.ssid?.trim('"') } catch (_: Exception) { null }
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
            LogBus.log(
                "!! the phone is not associated to any Wi‑Fi — connect with the dash's own app " +
                    "first (it brings the hotspot up), then tap Connect here",
            )
        } else {
            LogBus.log("→ phone is on Wi‑Fi '$ssid' — probing it for the bike")
        }

        // Android Auto path: hand off to BikeLink, which starts the probe once AA video is steady.
        // Anything else (Map / Mirror): probe right away. Never both — markWifiReady already
        // triggers maybeStartProbe, and a second start() would tear down the session it just built.
        if (gateOnAaSteady) {
            LogBus.log("→ network marked ready (waiting for AA video to go steady)")
            BikeLink.markWifiReady(null)
            return
        }
        ConnectionState.set(Phase.PXC_CONNECTING)
        try {
            (BikeLink.prober ?: prober).start(null)
        } catch (e: Exception) {
            LogBus.log("prober start failed: $e")
        }
    }

    /**
     * BLE "build net" path: the **phone** hosts the network and the dash joins it.
     *
     * Used by dashes whose pairing QR carries no Wi‑Fi credentials at all (Kove `action=128`), just
     * their BLE address. We raise a local-only hotspot, hand its generated SSID/passphrase to the
     * dash over BLE ([BleNetLink]), and it answers with the IP it took — which becomes the bike
     * address for the usual PXC probe. Same shape as the Wi‑Fi Direct path: no [android.net.Network]
     * of our own, explicit bind/gateway IPs instead.
     */
    private fun startBleBuildNet(qr: QrData, gateOnAaSteady: Boolean) {
        val mac = qr.bleMac ?: return
        pendingBleNet = qr to gateOnAaSteady
        if (!ensureBleNetPermissions()) return
        pendingBleNet = null

        // These dashes are paired with EasyConn's generic client, not the CFMoto one — announce
        // that package so the dash recognises us as a client it is allowed to project.
        // Present the whole vendor-client identity, not just its package: these dashes are paired
        // with EasyConn's client and may only project for a client they recognise.
        val btName = try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.name
        } catch (_: Exception) {
            null
        }
        EasyConnProber.useEasyRideIdentity(btName)
        // NI instrument cluster: the vendor never advertises touch here. A stale Force-touchscreen
        // setting otherwise put supportScreenTouch:true on the wire and the dash reset the control
        // socket ~10s in. Wire capture on the user's Kove omits the field — so do we, on this path.
        BikeProfileHolder.niClusterNoTouch = true
        // Back to the hardware encoder: the dash showed nothing with the software one either, so
        // that difference is ruled out, and on this phone software encoding drags the shared
        // Android Auto pipeline from a steady 30fps down to 3-15.
        VideoPipeline.preferSoftwareEncoder = false
        log("→ BLE build-net: the phone hosts the network, dash $mac joins it")
        log(
            "→ identity: ${EasyConnProber.spoofedPackage} v${EasyConnProber.spoofedVersionCode} " +
                "bt='${EasyConnProber.spoofedBluetoothName}' " +
                "supportFunction=${EasyConnProber.spoofedSupportFunction}",
        )
        log("→ transport: Wi‑Fi Direct group (phone = group owner), like the vendor client")
        ConnectionState.set(Phase.JOINING_WIFI)
        bleNetLink?.stop()

        val link = BleNetLink(
            context = applicationContext,
            mac = mac,
            log = LogBus::log,
            onCarNet = { car, name ->
                val bindIp = PhoneHotspot.localAddressFor(car)
                LogBus.log(
                    "→ dash '${name ?: "?"}' is on our hotspot at ${car.hostAddress}" +
                        " (our side: ${bindIp?.hostAddress ?: "unknown"})",
                )
                KoveLink.onDashJoined()
                if (gateOnAaSteady && bindIp != null) {
                    BikeLink.markP2pReady(bindIp, car)
                } else {
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    try {
                        (BikeLink.prober ?: prober).start(
                            network = null,
                            gatewayOverride = car,
                            bindIpOverride = bindIp,
                        )
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                ConnectionState.set(Phase.ERROR, reason)
                PhoneHotspot.stop(LogBus::log)
            },
            // Create the Wi‑Fi Direct group only once the BLE handshake is done and the dash is being
            // asked for build-net — NOT concurrently with the BLE connect. On 5GHz, bringing the P2P
            // group up during the GATT MTU/discovery phase disrupted the BLE link and the handshake
            // stalled after `requestMtu`, so the dash never received credentials and never joined.
            // The vendor creates its group at exactly this point (after the build-net request).
            onApNeeded = {
                PhoneHotspot.start(
                    context = applicationContext,
                    log = LogBus::log,
                    onReady = { info -> bleNetLink?.setApInfo(info, info.ip) },
                    onFailed = { reason ->
                        LogBus.log("!! $reason")
                        ConnectionState.set(Phase.ERROR, reason)
                        bleNetLink?.stop()
                    },
                )
            },
        )
        bleNetLink = link
        // Android Auto sessions get supervised; a mirror token cannot be reused after a rebuild.
        if (gateOnAaSteady) {
            KoveLink.arm(applicationContext, mac)
            KoveLink.attach(link)
        }
        link.start()
    }

    /**
     * BLE build-net needs Bluetooth at runtime; the local-only hotspot needs location.
     *
     * Asks through [bleNetPermissions] so a grant **resumes the connect on its own** — an earlier
     * version fired a bare `requestPermissions` with no result handler, so granting did nothing and
     * the session sat at "joining bike" with Android Auto already streaming.
     */
    private fun ensureBleNetPermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (p in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needed += p
                }
            }
        }
        // startLocalOnlyHotspot() is gated on NEARBY_WIFI_DEVICES from Android 13 on — without it
        // the framework throws SecurityException("does not have nearby devices permission") even
        // though location is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isEmpty()) return true
        log("BLE build-net is missing: ${needed.joinToString { it.substringAfterLast('.') }} — asking now")
        ConnectionState.set(Phase.JOINING_WIFI, "waiting for Bluetooth/location permission")
        bleNetPermissions.launch(needed.toTypedArray())
        return false
    }

    /** Wi‑Fi Direct Group Owner path (CL‑C450 / some DIRECT- SSIDs). */
    private fun joinWifiP2p(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        LogBus.log("→ joining via Wi‑Fi Direct (P2P)")
        BikeWifiP2p.connect(
            context = applicationContext,
            qr = qr,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(applicationContext)
                if (gateOnAaSteady) {
                    LogBus.log("→ P2P bound (waiting for AA video); bike=${gatewayIp.hostAddress}")
                    BikeLink.markP2pReady(bindIp, gatewayIp)
                } else {
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ P2P bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(
                            network = null,
                            gatewayOverride = gatewayIp,
                            bindIpOverride = bindIp,
                        )
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                LogBus.log("P2P join failed: $reason — falling back to AP join")
                if (!WifiGate.ensureEnabledOrNotify(applicationContext)) return@connect
                BikeWifi.join(
                    context = applicationContext,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    hidden = qr.hidden,
                    wpa3 = qr.isSae,
                    onAvailable = { network ->
                        WifiGate.cancelNotification(applicationContext)
                        if (gateOnAaSteady) BikeLink.markWifiReady(network)
                        else {
                            ConnectionState.set(Phase.PXC_CONNECTING)
                            try {
                                (BikeLink.prober ?: prober).start(network)
                            } catch (e: Exception) {
                                LogBus.log("prober start failed: $e")
                            }
                        }
                    },
                    onLost = { LogBus.log("bike network lost") },
                    log = LogBus::log,
                )
            },
            log = LogBus::log,
        )
    }

    private fun runBleWakeUpThenProber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ), 2,
            )
            // The user will need to tap Scan again after granting; keeping it simple for PoC.
            return
        }
        bleWakeUp?.stop()
        bleWakeUp = BleWakeUp(
            context = this,
            log = ::log,
            onUnlocked = {
                log("→ BLE wake-up OK; starting EasyConn prober …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason ->
                log("BLE wake-up failed: $reason — TCP probe likely useless, starting anyway")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
        ).also { it.start() }
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    /**
     * Home search bar — two modes depending on what's on the bike:
     *  1) Our Map projected → Nominatim search on the dash ([DashRemote])
     *  2) Android Auto live → hand off to Google Maps (route should appear on the dash)
     *  3) Nothing projected → open Map hub search on the phone
     */
    /** Waze's documented deep link. False when Waze cannot take it, so the caller can fall back. */
    private fun sendDestinationToWaze(dest: String): Boolean = try {
        log("→ sending destination to Waze")
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=${Uri.encode(dest)}&navigate=yes"))
                .setPackage("com.waze")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        log("Waze did not accept the destination: ${e.message}")
        false
    }

    private fun navigateToTyped() {
        val field = findViewById<android.widget.EditText>(R.id.et_destination)
        val dest = field.text?.toString()?.trim().orEmpty()
        if (dest.isEmpty()) {
            Toast.makeText(this, R.string.main_type_place, Toast.LENGTH_SHORT).show()
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(field.windowToken, 0)

        val aaLive = AaVideoBridge.pipeline != null
        val ourMapOnBike = GpxSession.active && DashRemote.isAvailable

        // 1) Our own map is on the bike — never send AA intents (would crash/steal Maps).
        if (ourMapOnBike && DashRemote.submit(dest)) {
            field.setText("")
            Toast.makeText(this, getString(R.string.main_searching_dash, dest), Toast.LENGTH_SHORT).show()
            return
        }
        // 2) Android Auto is live — resolve place then navigate (free-text google.navigation often
        //    crashes or won't let the rider pick a route on newer Maps / AA).
        if (aaLive && !GpxSession.active) {
            field.setText("")
            if (!sendDestinationToWaze(dest)) sendDestinationToAndroidAuto(dest)
            return
        }
        // Stale DashRemote with no map session: ignore and fall through.
        if (!aaLive && DashRemote.submit(dest)) {
            field.setText("")
            Toast.makeText(this, getString(R.string.main_searching_dash, dest), Toast.LENGTH_SHORT).show()
            return
        }
        // 3) Nothing projected → Map hub on the phone.
        GpxActivity.startSearch(this, dest)
    }

    /**
     * Look up [dest] (location-biased when possible), then fire Google Maps navigation with
     * lat/lng so Android Auto gets a clean destination instead of an ambiguous free-text query.
     */
    private fun sendDestinationToAndroidAuto(dest: String) {
        Toast.makeText(this, getString(R.string.main_sent_aa, dest), Toast.LENGTH_SHORT).show()
        log("[search] AA: looking up \"$dest\"…")
        val near = lastKnownLatLon()
        NominatimSearch.searchAsync(
            query = dest,
            nearLat = near?.first,
            nearLon = near?.second,
            onResult = { places ->
                runOnUiThread {
                    val best = places.firstOrNull()
                    if (best != null) {
                        // Prefer the place name (city) so Maps starts guidance to that place;
                        // bare lat/lon with a "(label)" suffix was opening Maps with an empty search.
                        val place = best.name.ifBlank { dest }
                        log("[search] AA: resolved → $place (${best.lat},${best.lon})")
                        if (!NavLauncher.navigate(this, place, ::log)) {
                            NavLauncher.navigateLatLon(this, best.lat, best.lon, place, ::log)
                        }
                    } else {
                        log("[search] AA: no geocode hit — free-text navigate \"$dest\"")
                        NavLauncher.navigate(this, dest, ::log)
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    log("[search] AA: geocode failed ($err) — free-text navigate \"$dest\"")
                    NavLauncher.navigate(this, dest, ::log)
                }
            },
        )
    }

    private fun lastKnownLatLon(): Pair<Double, Double>? {
        return try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) loc.latitude to loc.longitude else null
        } catch (_: Exception) {
            null
        }
    }

    private fun stopEverything() {
        log("→ stopping everything (Android Auto + bike)")
        KoveLink.disarm()
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(this) } catch (e: Exception) { log("AA stop: $e") }
        try { if (::prober.isInitialized) prober.stop() } catch (e: Exception) { log("prober stop: $e") }
        try { bleWakeUp?.stop() } catch (_: Exception) {}
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        try { GpxSession.clear() } catch (_: Exception) {}
        try { ProjectionService.stop(this) } catch (e: Exception) { log("projection stop: $e") }
        try { BikeWifi.leave(this, ::log) } catch (e: Exception) { log("wifi leave: $e") }
        try { BikeWifiP2p.stop(::log) } catch (e: Exception) { log("p2p stop: $e") }
        // BLE build-net bikes: drop the dash link and take our hotspot down — it would otherwise
        // stay up for the life of the process and keep the phone off its own Wi‑Fi.
        try { bleNetLink?.stop() } catch (_: Exception) {}
        bleNetLink = null
        // Deliberately NOT taking the hotspot down here. Android gives a local-only hotspot a fresh
        // random SSID every time it starts, and a dash that already joined the previous one answers
        // the next build-net with status=2 ("network already up") and never re-associates — the
        // session then hangs re-sending credentials for an SSID nothing is listening on. Keeping it
        // alive across Stop→Connect keeps the credentials the dash is holding valid. It is released
        // when the app process goes away, or via [releasePhoneHotspot].
        if (PhoneHotspot.isUp) log("[hotspot] left up so the dash keeps its credentials")
        ConnectionState.set(Phase.STOPPED, "")
    }

    /**
     * Map button always opens the Hub — pick free ride / route / search / offline there.
     * Starting a ride projects to the bike (no AA); a "See map on phone" button covers
     * off-bike navigation (e.g. walking back to a parked bike).
     */
    private fun onMapPressed() {
        // Don't carry a killed Campina/etc. NAV_TO into the hub → phone map.
        if (ConnectionState.phase != Phase.STREAMING && ConnectionState.phase != Phase.MIRRORING) {
            GpxSession.abandonStaleNavigation("map hub")
        }
        log("→ Map: open Hub")
        GpxActivity.start(this)
    }

    /**
     * Mirror button: if already projecting, arm screen capture and re-link as mirror;
     * otherwise start mirror flow (consent then connect).
     */
    private fun onMirrorPressed() {
        log("→ Mirror: cast phone screen to dash")
        pendingAaStart = false
        ensureLocationPermission()
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                Toast.makeText(
                    this,
                    "Single-app mirror needs Android 14+. Whole screen will be used.",
                    Toast.LENGTH_LONG,
                ).show()
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        } catch (e: Exception) {
            log("mirror start failed ($e)")
        }
    }

    private fun shareLog() {
        try {
            CrashGuard.persistSession(this)
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uris = ArrayList<Uri>()
            uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))

            val crash = CrashGuard.crashFile(this)
            if (crash.exists() && crash.length() > 0L) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", crash))
                log("attaching crash report: ${crash.name} (${crash.length()} bytes)")
            }

            // Attach any diagnostic H.264 dumps (VideoPipeline writes these to <externalFiles>/video).
            val videoDir = File(getExternalFilesDir(null), "video")
            val dumps = videoDir.listFiles { f -> f.name.endsWith(".h264") }?.sortedBy { it.name } ?: emptyList()
            for (d in dumps) {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", d))
                log("attaching video dump: ${d.name} (${d.length()} bytes)")
            }

            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = if (uris.size > 1) "*/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                else putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("share failed: $e")
        }
    }

    private fun checkUpdateManual() = UpdateUi.checkManual(this)

    /** "v1.1.0" normally; "v1.1.0 · new v1.2.0" in brand color when a newer release is known. */
    private fun refreshUpdateBadge() {
        val label = findViewById<TextView>(R.id.brand_version)
        val newer = UpdateChecker.available(this)
        if (newer == null) {
            label.text = "v${BuildConfig.VERSION_NAME}"
            label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            label.setOnClickListener { AboutActivity.start(this) }
        } else {
            label.text = getString(R.string.main_update_available, BuildConfig.VERSION_NAME, newer.version)
            label.setTextColor(ContextCompat.getColor(this, R.color.brand_orange))
            label.setOnClickListener { UpdateUi.show(this, newer) }
        }
    }

    private fun reportProblem() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val problem = android.widget.EditText(this).apply {
            hint = context.getString(R.string.main_report_hint_problem)
            minLines = 3
            setPadding(pad, pad, pad, pad)
        }
        val model = android.widget.EditText(this).apply {
            hint = context.getString(R.string.main_report_hint_bike_model)
            setText(BikeMemory.lastBikeName(this@MainActivity).orEmpty())
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val year = android.widget.EditText(this).apply {
            hint = context.getString(R.string.main_report_hint_year)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(problem)
            addView(model)
            addView(year)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.main_report_a_problem)
            .setMessage(R.string.main_report_problem_message)
            .setView(box)
            .setPositiveButton(R.string.main_share) { _, _ ->
                shareProblemReport(
                    problem.text.toString(),
                    model.text.toString(),
                    year.text.toString(),
                )
            }
            .setNegativeButton(R.string.dash_cancel, null)
            .show()
    }

    private fun shareProblemReport(problem: String, model: String, year: String) {
        try {
            val diagnostics = buildString {
                appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("profile=${BikeProfileHolder.active.name}")
                appendLine("override=${BikeProfileHolder.profileOverride}")
                appendLine("transport=${AppSettings.transport(this@MainActivity).label}")
                appendLine("margins=${ScreenMargins.summary()}")
                appendLine("forceNonTouch=${BikeProfileHolder.forceNonTouch}")
                appendLine("forceTouch=${BikeProfileHolder.forceTouch}")
                appendLine("ssid=${BikeMemory.lastQr(this@MainActivity)?.ssid ?: "—"}")
                appendLine("phase=${ConnectionState.phase} ${ConnectionState.detail}")
            }
            val text = ProblemReport.file(
                problem = problem.ifBlank { "(not specified)" },
                model = model,
                year = year,
                diagnostics = diagnostics,
                log = LogBus.snapshot(),
            )
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "opencfmoto-report.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, ProblemReport.subject(model, BuildConfig.VERSION_NAME))
                putExtra(Intent.EXTRA_TEXT, ProblemReport.body(problem, model, year, diagnostics))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.main_share_problem_report)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.main_share_report_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun log(msg: String) = LogBus.log(msg)

    /** After screen-capture consent: connect using saved bike or scan. */
    private fun startMirrorLink() {
        val saved = BikeMemory.lastQr(this)
        if (saved != null) {
            log("→ Mirror: reusing saved bike '${BikeMemory.lastBikeName(this)}'")
            // Drop AA / Map / old PXC; keep the MediaProjection token we just armed.
            tearDownForModeSwitch(clearMap = true, clearMirror = false)
            applyProfile(saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            // Single-app MediaProjection only emits while the shared app is visible. Leaving this
            // Activity on top → capture visible=false → black dash / framesSent=0.
            moveTaskToBack(true)
            Toast.makeText(
                this,
                getString(R.string.main_mirror_leave_shared_app),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            log("→ Mirror: scan the dash QR…")
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
    }

    /**
     * Map / GPX session ready.
     * Always hard-switches: stop previous projection, reopen PXC with Map video (keep Wi‑Fi).
     * Phone Dash preview only when not sending to the bike.
     */
    private fun beginGpxProjection() {
        if (!GpxSession.active) {
            log("→ Map: nothing prepared — open Map / GPX first")
            GpxActivity.start(this)
            return
        }
        pendingAaStart = false
        ensureLocationPermission()
        val live = ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING ||
            ConnectionState.phase == Phase.RECONNECTING ||
            ConnectionState.phase == Phase.PXC_CONNECTING ||
            ConnectionState.phase == Phase.STARTING_AA ||
            ConnectionState.phase == Phase.AA_VIDEO_LIVE ||
            ConnectionState.phase == Phase.JOINING_WIFI
        val wantBike = intent?.getBooleanExtra(EXTRA_GPX_TO_BIKE, false) == true || live
        if (wantBike) intent?.removeExtra(EXTRA_GPX_TO_BIKE)
        val saved = BikeMemory.lastQr(this)
        if (wantBike && saved != null) {
            log(
                "→ Map: ${GpxSession.mode} '${GpxSession.trackName}' → " +
                    "'${BikeMemory.lastBikeName(this)}' (hard switch)",
            )
            // Stop AA / mirror / old sockets, keep Wi‑Fi + GpxSession, then fresh PXC.
            tearDownForModeSwitch(clearMap = false, clearMirror = true)
            applyProfile(saved)
            ConnectionState.set(Phase.MIRRORING, BikeMemory.lastBikeName(this) ?: saved.ssid)
            joinWifi(saved, gateOnAaSteady = false)
            return
        }
        log("→ Map: phone Dash preview (${GpxSession.mode} '${GpxSession.trackName}')")
        HudViewActivity.startGpxPreview(this)
    }

    companion object {
        /** Max chars kept in the on-screen log TextView; above this we drop the oldest half. Bounds
         *  memory and avoids the ContentCapture TransactionTooLargeException on long sessions. */
        private const val LOG_VIEW_MAX_CHARS = 120_000
        const val EXTRA_START_GPX = "start_gpx"
        const val EXTRA_START_SCAN = "start_scan"
        /** When set with [EXTRA_START_GPX], join the bike even if not already live. */
        const val EXTRA_GPX_TO_BIKE = "gpx_to_bike"
        /** Latched once an auto-connect attempt actually starts, so it fires only once per process. */
        @Volatile private var autoConnectStarted = false
        /** So the "idle/not-in-range" reason is logged once, not on every onResume retry. */
        @Volatile private var autoConnectSkipLogged = false
    }
}
