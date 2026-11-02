// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The network the phone hosts for dashes that join *us* instead of hosting a hotspot themselves.
 *
 * ## Why Wi‑Fi Direct and not a hotspot
 * This started as [android.net.wifi.WifiManager.startLocalOnlyHotspot], which works — the dash
 * associates, takes an address and completes the whole PXC handshake — but it never projects.
 * Captured from the vendor client on a session that *does* project:
 * ```
 * ConnectivityService: Adding iface p2p0 to network 178
 * MDNSClient: Find --->>> p2p0, /192.168.49.1
 * ECP_C2P_CLIENT_INFO: checkIsP2pAddress: ip1[192,168,49,1] ip2[192,168,49,139] interfaceName:p2p0
 * ```
 * It runs the session over a **Wi‑Fi Direct group** — phone as group owner on 192.168.49.1, dash as
 * a legacy client — and the SDK explicitly checks whether the link is a P2P address. A local-only
 * hotspot lands on `wlan0` in a different subnet, which is why the dash treats the session as not
 * fully established.
 *
 * ## Why the group is named `Easyconn_AP-NNNN`
 * The vendor client does **not** take the framework's auto-generated `DIRECT-xx-…` name. Its
 * `WifiHotspotUtils.createCustomAP_p()` builds a config with placeholder credentials and then
 * overwrites `WifiP2pConfig`'s private `networkName` / `passphrase` fields by reflection — the only
 * way past the framework rule that a P2P network name must start with `DIRECT-`. The value it puts
 * there comes from `WifiApUtils`:
 * ```java
 * public static final String PREFIX = "Easyconn_AP-";        // + 4 random digits
 * generatePassphrase() → 12 random chars from [a-z0-9]
 * ```
 * That is the `Easyconn_AP-xxxx` network seen next to one of these bikes. A dash joined to a
 * `DIRECT-…` group completes the whole PXC session but never lights its Wi‑Fi indicator, so it is
 * worth matching the name the firmware expects. If the reflection is blocked we fall back to the
 * framework default, which is no worse than before.
 *
 * The credentials are handed to the dash over BLE ([BleNetLink]), exactly as the vendor client
 * hands over its own.
 */
@SuppressLint("MissingPermission")
object PhoneHotspot {

    data class Info(
        val ssid: String,
        val passphrase: String,
        /** The vendor client sends "WPA2" here, not the "wpa2-psk" spelling used in QR payloads. */
        val auth: String = "WPA2",
        /** Group owner address — `WifiP2pInfo.groupOwnerAddress`, what the vendor puts in `ip`. */
        val ip: String? = null,
    )

    /** Standard Wi‑Fi Direct group-owner address. The phone (group owner) is always here, so this is
     *  the correct `ip` for PhoneApInfo even before [WifiP2pInfo.groupOwnerAddress] is captured. */
    const val GROUP_OWNER_IP = "192.168.49.1"

    /** The p2p0 interface BSSID (our AP's MAC) if the OS lets us read it, else null. The vendor puts
     *  this in PhoneApInfo.mac so the dash can verify it joined the phone's AP (not a rogue one). On
     *  Android with MAC-access restrictions this may return null/anonymized — we then send "". */
    fun p2pBssid(): String? = try {
        java.net.NetworkInterface.getByName("p2p0")?.hardwareAddress
            ?.takeIf { it.size == 6 && it.any { b -> b.toInt() != 0 } }
            ?.joinToString(":") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** Kept so the group builder can reach [HiddenApi], which needs the APK path. */
    private var appContext: Context? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var current: Info? = null
        private set

    val isUp: Boolean get() = current != null

    /** Group info isn't always readable the instant createGroup() succeeds. */
    private const val GROUP_POLL_MS = 400L
    private const val GROUP_POLL_TRIES = 15

    fun start(
        context: Context,
        log: (String) -> Unit,
        onReady: (Info) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        current?.let {
            log("[p2p] group already up: ssid=${it.ssid}")
            onReady(it)
            return
        }
        val app = context.applicationContext
        appContext = app
        val mgr = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            onFailed("this phone has no Wi‑Fi Direct support")
            return
        }
        val ch = try {
            mgr.initialize(app, Looper.getMainLooper(), null)
        } catch (e: Exception) {
            onFailed("could not initialize Wi‑Fi Direct: $e"); return
        }
        if (ch == null) {
            onFailed("could not initialize Wi‑Fi Direct (is Wi‑Fi on?)")
            return
        }
        manager = mgr
        channel = ch

        // A leftover group from a previous run keeps its old credentials, which the dash no longer
        // has — always start from a clean one.
        log("[p2p] creating Wi‑Fi Direct group (phone = group owner) …")
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = createGroup(mgr, ch, log, onReady, onFailed)
                override fun onFailure(reason: Int) = createGroup(mgr, ch, log, onReady, onFailed)
            })
        } catch (e: Exception) {
            createGroup(mgr, ch, log, onReady, onFailed)
        }
    }

    private fun createGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        log: (String) -> Unit,
        onReady: (Info) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("[p2p] group requested — waiting for credentials")
                pollGroup(mgr, ch, GROUP_POLL_TRIES, log, onReady, onFailed)
            }

            override fun onFailure(reason: Int) {
                onFailed("Wi‑Fi Direct group creation refused (${describe(reason)})")
            }
        }
        try {
            val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) easyconnConfig(log) else null
            if (named != null) {
                mgr.createGroup(ch, named, listener)
                return
            }
            mgr.createGroup(ch, listener)
        } catch (e: SecurityException) {
            onFailed("permission denied creating the Wi‑Fi Direct group ($e)")
        } catch (e: Exception) {
            onFailed("could not create the Wi‑Fi Direct group: $e")
        }
    }

    /** `WifiApUtils.PREFIX` + 4 digits, and a 12-char passphrase — the vendor's alphabets verbatim. */
    private const val AP_PREFIX = "Easyconn_AP-"
    private const val NAME_DIGITS = "1234567890"
    private const val PASS_CHARS = "abcdefghijklmnopqrstuvwxyz1234567890"

    /**
     * The vendor's `createCustomAP_p()`: a config the framework will accept, with the real name and
     * passphrase written straight into the private fields afterwards. Returns null when the
     * reflection is unavailable, so the caller can fall back to the framework's own naming.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun easyconnConfig(log: (String) -> Unit): WifiP2pConfig? {
        // `networkName`/`passphrase` are non-SDK members: without this they don't just refuse to be
        // written, they report themselves as not existing. The vendor client lifts the same
        // restriction on startup (me.weishu.reflection) — that is why its group is Easyconn_AP-xxxx.
        HiddenApi.unseal(appContext, log)
        return try {
            // Reuse across rides, like the vendor: `createCustomAP_p()` only calls
            // `WifiApUtils.generateNetworkName()` when its saved one is empty, and `savePreApInfo()`
            // persists the pair. A fresh random SSID every session means the dash has to discover an
            // unknown network each time instead of rejoining one it already knows — which is why
            // joining was hit-and-miss across runs.
            val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = prefs?.getString(KEY_AP_SSID, null)
                ?: (AP_PREFIX + (1..4).map { NAME_DIGITS.random() }.joinToString(""))
            val pass = prefs?.getString(KEY_AP_PASS, null)
                ?: (1..12).map { PASS_CHARS.random() }.joinToString("")
            prefs?.edit()?.putString(KEY_AP_SSID, name)?.putString(KEY_AP_PASS, pass)?.apply()
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(PLACEHOLDER_NAME)
                .setPassphrase(PLACEHOLDER_PASS)
                .setGroupOperatingBand(GROUP_BAND)
                .build()
            // The vendor names the fields directly (`networkName`, `passphrase`), which this
            // platform doesn't expose — renamed or hidden, both of which surface as
            // NoSuchFieldException. Find them by the placeholder we just put there instead, so the
            // field names stop mattering.
            val renamed = overwriteStringField(cfg, PLACEHOLDER_NAME, name)
            overwriteStringField(cfg, PLACEHOLDER_PASS, pass)
            // Even when the name can't be changed the config is still worth using: it is what
            // carries the 5GHz band the vendor asks for. Falling back to the no-argument
            // createGroup() would hand the whole choice to the framework, which picks 2.4GHz.
            log(
                "[p2p] hosting on ${if (GROUP_BAND == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) "5GHz" else "2.4GHz"}" +
                    " as '${if (renamed) name else PLACEHOLDER_NAME}'" +
                    if (renamed) " (vendor's Easyconn_AP- naming)"
                    else " (platform blocks the Easyconn_AP- rename; band and passphrase still match)",
            )
            cfg
        } catch (e: Exception) {
            log("[p2p] can't rename the group ($e) — using the framework's DIRECT- default")
            null
        }
    }

    /**
     * 5GHz, exactly like the vendor (`setGroupOperatingBand(2)`). Its working session on the user's
     * own Kove created the group at `freq=5220` / `5180` (5GHz), and the dash joined ~5s later — so
     * the dash does find and join a renamed 5GHz group. Matching the vendor 1:1 here is a hard
     * requirement; the earlier 2.4GHz choice was a scan-speed guess, not something the vendor does.
     */
    private const val GROUP_BAND = WifiP2pConfig.GROUP_OWNER_BAND_5GHZ

    /** Where the reused SSID/passphrase live — the vendor's `SP_BLE_NET_BUILD_AP_INFO`. */
    private const val PREFS = "opencfmoto.hotspot"
    private const val KEY_AP_SSID = "ap_ssid"
    private const val KEY_AP_PASS = "ap_pass"

    private const val PLACEHOLDER_NAME = "DIRECT-xy-opencfmoto"
    private const val PLACEHOLDER_PASS = "11111111"

    /** Replace whichever declared String field currently holds [expected]. */
    private fun overwriteStringField(target: Any, expected: String, value: String): Boolean {
        for (f in target.javaClass.declaredFields) {
            if (f.type != String::class.java) continue
            try {
                f.isAccessible = true
                if (f.get(target) == expected) { f.set(target, value); return true }
            } catch (_: Exception) {
                // Field not reachable on this platform — keep looking.
            }
        }
        return false
    }

    private fun pollGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        triesLeft: Int,
        log: (String) -> Unit,
        onReady: (Info) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        try {
            mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                val ssid = group?.networkName
                val pass = group?.passphrase
                if (group != null && group.isGroupOwner && !ssid.isNullOrBlank() && !pass.isNullOrBlank()) {
                    // The passphrase is a live credential — LogRedactor scrubs it from exports.
                    log("[p2p] group up: ssid=$ssid iface=${group.`interface`} owner=${group.isGroupOwner}")
                    // The vendor fills the AP payload's `ip` from the group owner address:
                    //   ip = wifiP2pInfo.groupOwnerAddress.getHostAddress()   (checkConnectionInfo)
                    // and then picks the 5-argument sendHotspot2Car() precisely when that is set.
                    // Ours went out without the field at all, so the dash was told the network but
                    // not where to find us on it.
                    mgr.requestConnectionInfo(ch) { p2pInfo ->
                        val ownerIp = p2pInfo?.groupOwnerAddress?.hostAddress
                        val info = Info(ssid, pass, ip = ownerIp)
                        current = info
                        log("[p2p] group owner address: ${ownerIp ?: "(unknown — AP payload will omit ip)"}")
                        onReady(info)
                    }
                    return@requestGroupInfo
                }
                if (triesLeft <= 0) {
                    onFailed("Wi‑Fi Direct group came up without readable credentials")
                    return@requestGroupInfo
                }
                handler.postDelayed(
                    { pollGroup(mgr, ch, triesLeft - 1, log, onReady, onFailed) },
                    GROUP_POLL_MS,
                )
            }
        } catch (e: Exception) {
            onFailed("could not read the Wi‑Fi Direct group: $e")
        }
    }

    fun stop(log: (String) -> Unit) {
        val mgr = manager
        val ch = channel
        current = null
        handler.removeCallbacksAndMessages(null)
        if (mgr == null || ch == null) return
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = log("[p2p] group removed")
                override fun onFailure(reason: Int) = log("[p2p] group remove failed (${describe(reason)})")
            })
        } catch (e: Exception) {
            log("[p2p] group remove failed: $e")
        }
    }

    /**
     * Our IPv4 on the group interface, derived from the address the dash reports: the group owner is
     * whatever local address shares the dash's /24 (192.168.49.1 on a standard Wi‑Fi Direct group).
     * Picking by subnet rather than interface name avoids guessing at OEM naming.
     */
    fun localAddressFor(peer: Inet4Address): Inet4Address? {
        val want = peer.address
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in nic.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val a = addr.address
                    if (a[0] == want[0] && a[1] == want[1] && a[2] == want[2] && a[3] != want[3]) {
                        return addr
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun describe(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi‑Fi Direct unsupported on this phone"
        WifiP2pManager.BUSY -> "the Wi‑Fi Direct framework is busy — retry in a moment"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "code $reason"
    }
}
