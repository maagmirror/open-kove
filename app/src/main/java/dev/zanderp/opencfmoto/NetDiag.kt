// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Dumps every network the phone currently has, so a failed bike link can be told apart from a bike
 * link that was never reachable in the first place.
 *
 * Needed for the "use current Wi‑Fi" transport ([WifiTransport.EXISTING]): when another app brings
 * the dash hotspot up, whether *we* can use it depends on **how** that app joined. A join made with
 * `WifiNetworkSpecifier` is handed only to the requesting app — the phone associates, but the
 * network never becomes the default route and other apps cannot bind to it. A normal/system join
 * (saved network) is shared. Both look identical to the rider ("the phone says it's connected"),
 * and only this dump tells them apart: look for a `TRANSPORT_WIFI` entry whose SSID is the dash's
 * and whose gateway is the bike (192.168.0.1 on EasyConn units).
 */
object NetDiag {

    /** Log all networks, the active one, and every local IPv4 interface. */
    fun dump(context: Context, log: (String) -> Unit) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        log("[net] ===== network diagnostic =====")

        val active = cm.activeNetwork
        log("[net] active network: ${active ?: "(none)"}")

        val all = try { cm.allNetworks } catch (_: Exception) { emptyArray() }
        log("[net] networks visible to this app: ${all.size}")
        for (n in all) {
            val caps = cm.getNetworkCapabilities(n)
            val lp = cm.getLinkProperties(n)
            val transports = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELL")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETH")
            }.joinToString("+").ifEmpty { "?" }
            val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isActive = n == active
            log("[net] $n  $transports  internet=$internet${if (isActive) "  ← ACTIVE" else ""}")

            // The SSID is only exposed on the network's WifiInfo, and only with location permission.
            (caps?.transportInfo as? WifiInfo)?.let { wi ->
                log(
                    "[net]     ssid=${wi.ssid} bssid=${wi.bssid} " +
                        "freq=${wi.frequency}MHz rssi=${wi.rssi}dBm",
                )
            }
            lp?.let { p ->
                log("[net]     iface=${p.interfaceName} addrs=${p.linkAddresses.joinToString()}")
                val gw = p.routes.firstOrNull { it.isDefaultRoute }?.gateway
                log("[net]     default gateway=${gw?.hostAddress ?: "(none)"}")
            }
        }

        // The system-level Wi-Fi association, which may differ from what this app can bind to.
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val info = try { wm?.connectionInfo } catch (_: Exception) { null }
        if (info != null) {
            log(
                "[net] system Wi-Fi: ssid=${info.ssid} bssid=${info.bssid} " +
                    "freq=${info.frequency}MHz ip=${ipv4String(info.ipAddress)}",
            )
        }

        // Raw interfaces: a hotspot another app joined still shows up here even when the Network
        // object is private to that app — that asymmetry is the whole diagnosis.
        log("[net] local IPv4 interfaces:")
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        log("[net]     ${nic.name}: ${addr.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            log("[net] interface enumeration failed: $e")
        }
        log("[net] ===== end diagnostic =====")
    }

    /**
     * Every real IPv4 interface as `address to netmask`, skipping the virtual and carrier ones the
     * vendor SDK also filters out — a dash handed a `rmnet`/`tun` address would try to reach the
     * phone over a route that does not lead back to it.
     */
    fun localIpv4s(): List<Pair<String, String>> {
        val skipPrefix = listOf(
            "oem", "nm_", "tun", "qcom_", "dummy", "lo", "ifb", "sit", "usb", "tunl", "bond", "ccmni",
        )
        val out = mutableListOf<Pair<String, String>>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                val name = nic.name ?: continue
                if (skipPrefix.any { name.startsWith(it, ignoreCase = true) }) continue
                if (name.contains("rmnet", ignoreCase = true)) continue
                for (ia in nic.interfaceAddresses) {
                    val addr = ia.address
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isAnyLocalAddress) continue
                    val mask = -1 shl (32 - ia.networkPrefixLength)
                    out += (addr.hostAddress ?: continue) to
                        "${(mask ushr 24) and 0xFF}.${(mask shr 16) and 0xFF}." +
                        "${(mask shr 8) and 0xFF}.${mask and 0xFF}"
                }
            }
        } catch (_: Exception) {
            // An enumeration failure just means we describe no interfaces, which is a valid answer.
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun ipv4String(raw: Int): String =
        "${raw and 0xFF}.${(raw shr 8) and 0xFF}.${(raw shr 16) and 0xFF}.${(raw shr 24) and 0xFF}"
}
