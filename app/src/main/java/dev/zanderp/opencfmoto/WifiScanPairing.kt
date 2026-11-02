// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Pair a bike by **scanning for its hotspot** instead of by QR.
 *
 * Some dashes ship a Carbit/EasyConn pairing QR that carries no credentials at all — e.g. Kove:
 * `…/down6/642/643/_ylqx?modelid=21318&sn=JRGk&action=128&bm=…` has no `ssid`/`pwd`, so
 * [QrData.parse] can't produce anything joinable. Those units still bring up a normal SoftAP
 * (`Easyconn_AP-xxxx`), so the rider can pick it out of a Wi‑Fi scan and type the passphrase once.
 *
 * The result is the same synthetic pairing URL [ManualWifiPairing] produces ([QrData.buildManual]),
 * so everything downstream — [BikeMemory], [BikeProfiles.selectByQr], the join path — is unchanged.
 *
 * Everything here is diagnostic-friendly: each step logs through [LogBus] so a failed first pairing
 * can be read off a single Logs export.
 */
object WifiScanPairing {

    /** SSID fragments that mark a head unit rather than a house/phone network (ranked first). */
    private val DASH_HINTS = listOf(
        "easyconn", "carbit", "cfmoto", "kove", "benelli", "voge", "qjmotor", "qj-",
        "motoplay", "motofun", "morini", "direct-", "moto",
    )

    /** How long to keep the picker refreshing from scan callbacks before giving up on new APs. */
    private const val SCAN_WINDOW_MS = 25_000L
    private const val RESCAN_INTERVAL_MS = 6_000L

    private data class Ap(
        val ssid: String,
        val bssid: String?,
        val level: Int,
        val frequency: Int,
        val capabilities: String,
    ) {
        val isOpen: Boolean
            get() = !capabilities.contains("WPA", true) &&
                !capabilities.contains("WEP", true) &&
                !capabilities.contains("SAE", true) &&
                !capabilities.contains("PSK", true)
        val isSaeOnly: Boolean
            get() = capabilities.contains("SAE", true) && !capabilities.contains("WPA2", true)
        val band: String
            get() = when {
                frequency in 2400..2500 -> "2.4 GHz"
                frequency in 4900..5900 -> "5 GHz"
                frequency > 5900 -> "6 GHz"
                else -> "${frequency} MHz"
            }
        val security: String
            get() = when {
                isOpen -> "open"
                isSaeOnly -> "WPA3"
                capabilities.contains("WPA2", true) -> "WPA2"
                capabilities.contains("WPA3", true) -> "WPA2/3"
                else -> "secured"
            }
        val looksLikeDash: Boolean
            get() = DASH_HINTS.any { ssid.contains(it, ignoreCase = true) }
    }

    /**
     * Show the picker. [identity] is the optional identity-only QR the rider just scanned — its
     * `modelid` / `sn` are carried into the saved pairing so profile selection still works.
     */
    fun show(
        activity: AppCompatActivity,
        identity: QrData.Companion.Identity? = null,
        onPaired: (raw: String, qr: QrData) -> Unit,
    ) {
        if (!WifiGate.ensureEnabledOrPrompt(activity)) return
        if (!ensureScanPermission(activity)) return

        val wm = activity.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            Toast.makeText(activity, "Wi‑Fi not available on this phone", Toast.LENGTH_LONG).show()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val status = TextView(activity).apply {
            text = "Scanning…"
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(
                ScrollView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(320),
                    )
                    addView(list)
                },
            )
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Pick the bike's Wi‑Fi")
            .setMessage(
                "Turn the dash on and open its phone-link / EasyConn screen so the hotspot is up. " +
                    "Dash networks are listed first (e.g. Easyconn_AP-…).",
            )
            .setView(root)
            .setNeutralButton("Not listed / hidden", null)
            .setNegativeButton("Close", null)
            .create()

        val handler = Handler(Looper.getMainLooper())
        var receiver: BroadcastReceiver? = null
        val startedAt = SystemClock.elapsedRealtime()

        fun stopScanning() {
            handler.removeCallbacksAndMessages(null)
            receiver?.let { r -> runCatching { activity.unregisterReceiver(r) } }
            receiver = null
        }

        fun render(aps: List<Ap>) {
            list.removeAllViews()
            if (aps.isEmpty()) {
                status.text = "No networks yet — keep the dash's pairing screen open."
                return
            }
            status.text = "${aps.size} network(s) — tap the bike's one"
            for (ap in aps) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    isClickable = true
                    setOnClickListener {
                        stopScanning()
                        dialog.dismiss()
                        LogBus.log(
                            "[pair] picked AP '${ap.ssid}' bssid=${ap.bssid} " +
                                "${ap.band} ${ap.level}dBm caps=${ap.capabilities}",
                        )
                        askPassphrase(activity, ap, identity, onPaired)
                    }
                }
                row.addView(
                    TextView(activity).apply {
                        text = if (ap.looksLikeDash) "★  ${ap.ssid}" else ap.ssid
                        textSize = 16f
                    },
                )
                val warn = if (ap.frequency >= 4900) "  •  5 GHz — some phones can't join" else ""
                row.addView(
                    TextView(activity).apply {
                        text = "${ap.band}  •  ${ap.level} dBm  •  ${ap.security}$warn"
                        textSize = 12f
                        alpha = 0.7f
                    },
                )
                list.addView(row)
            }
        }

        fun collect(): List<Ap> {
            @Suppress("DEPRECATION")
            val results: List<ScanResult> = try {
                wm.scanResults ?: emptyList()
            } catch (e: SecurityException) {
                LogBus.log("[pair] scan results denied: $e")
                emptyList()
            }
            // One row per SSID, keeping the strongest BSSID; dash-looking names first.
            val best = LinkedHashMap<String, Ap>()
            for (r in results) {
                @Suppress("DEPRECATION")
                val ssid = r.SSID?.trim()?.trim('"').orEmpty()
                if (ssid.isEmpty()) continue // hidden AP — use the "Not listed / hidden" path
                val ap = Ap(ssid, r.BSSID, r.level, r.frequency, r.capabilities.orEmpty())
                val prev = best[ssid]
                if (prev == null || ap.level > prev.level) best[ssid] = ap
            }
            return best.values.sortedWith(
                compareByDescending<Ap> { it.looksLikeDash }.thenByDescending { it.level },
            )
        }

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                render(collect())
            }
        }
        receiver = rx
        ContextCompat.registerReceiver(
            activity,
            rx,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Android throttles startScan() hard (4 calls / 2 min on most builds) — the cached results
        // are what actually fill the list, so render them immediately and re-scan on a slow tick.
        fun tick() {
            @Suppress("DEPRECATION")
            val started = try { wm.startScan() } catch (e: Exception) {
                LogBus.log("[pair] startScan failed: $e"); false
            }
            if (!started) LogBus.log("[pair] startScan throttled — using cached scan results")
            render(collect())
            if (SystemClock.elapsedRealtime() - startedAt < SCAN_WINDOW_MS) {
                handler.postDelayed({ tick() }, RESCAN_INTERVAL_MS)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                stopScanning()
                dialog.dismiss()
                ManualWifiPairing.show(activity, hiddenDefault = true, identity = identity, onPaired = onPaired)
            }
            tick()
        }
        dialog.setOnDismissListener { stopScanning() }
        LogBus.log("[pair] Wi‑Fi scan pairing opened" + (identity?.let { " (QR modelid=${it.modelId} action=${it.action} bm=${it.bm})" } ?: ""))
        dialog.show()
    }

    /** Scan results need location permission; without it [WifiManager.getScanResults] returns empty. */
    private fun ensureScanPermission(activity: AppCompatActivity): Boolean {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (needed.isEmpty()) return true
        ActivityCompat.requestPermissions(activity, needed.toTypedArray(), 4242)
        Toast.makeText(
            activity,
            "Allow location so Android can list nearby Wi‑Fi, then tap again",
            Toast.LENGTH_LONG,
        ).show()
        LogBus.log("[pair] requesting scan permissions: ${needed.joinToString()}")
        return false
    }

    /** Ask for the AP passphrase (skipped for an open network) and hand back a pairing URL. */
    private fun askPassphrase(
        activity: AppCompatActivity,
        ap: Ap,
        identity: QrData.Companion.Identity?,
        onPaired: (raw: String, qr: QrData) -> Unit,
    ) {
        fun finish(pwd: String) {
            val built = QrData.buildManual(
                ssid = ap.ssid,
                pwd = pwd,
                displayName = ap.ssid,
                hidden = false,
                auth = when {
                    ap.isOpen -> "none"
                    ap.isSaeOnly -> "sae"
                    else -> "wpa2-psk"
                },
                modelId = identity?.modelId,
                sn = identity?.sn,
            )
            if (built == null) {
                Toast.makeText(activity, "Couldn't build the pairing", Toast.LENGTH_SHORT).show()
                return
            }
            LogBus.log(
                "[pair] saved '${ap.ssid}' (${ap.security}, ${ap.band})" +
                    (identity?.modelId?.let { " modelid=$it" } ?: ""),
            )
            onPaired(built.first, built.second)
        }

        if (ap.isOpen) {
            finish("")
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val til = TextInputLayout(activity).apply {
            hint = "Password for ${ap.ssid}"
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val et = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        til.addView(et)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Bike Wi‑Fi password")
            .setMessage(
                "${ap.ssid} is ${ap.security}. The passphrase is set by the dash — check its " +
                    "pairing/Wi‑Fi screen, the owner's manual, or a device already connected to it.",
            )
            .setView(til)
            .setPositiveButton("Add bike", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pwd = et.text?.toString().orEmpty()
                if (pwd.length < 8) {
                    Toast.makeText(
                        activity,
                        "A WPA2 passphrase is at least 8 characters",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                finish(pwd)
            }
        }
        dialog.show()
    }
}
