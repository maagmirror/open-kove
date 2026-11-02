// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.net.Network
import java.net.Inet4Address

/**
 * Process-global handle to the bike PXC client ([EasyConnProber]).
 *
 * Held here — not only as a [MainActivity] field — so the Android Auto → bike hand-off and the
 * Stop control survive a [MainActivity] recreation. Triggering Google Android Auto (self-mode)
 * brings Gearhead to the foreground, which can destroy and recreate [MainActivity] while the AA
 * receiver keeps running in [AndroidAutoService]. The bike connection must not be torn down or
 * orphaned when that happens: a fresh activity re-reads the SAME prober instance from here instead
 * of constructing a new one that would leave the running one leaked and unstoppable.
 *
 * Matches the existing process-global style ([AaVideoBridge], [ProjectionHolder], [BikeProfileHolder]).
 */
object BikeLink {
    @Volatile var prober: EasyConnProber? = null

    // ---- Android Auto → bike start coordination (parallel-startup gate) ----
    // The two slow steps used to be serial: wait for AA "steady video", THEN pop the Wi-Fi join
    // dialog, THEN probe the bike. We now kick off the Wi-Fi join IN PARALLEL with AA boot and only
    // start the probe once BOTH are ready — the user accepts the Wi-Fi dialog while AA is still
    // spinning up, shaving several seconds. The bike still never gets probed before AA has frames to
    // serve, so streaming correctness is unchanged. Lives here so a MainActivity recreation
    // mid-startup doesn't lose the pending gate.
    @Volatile private var aaVideoSteady = false
    @Volatile private var bikeNetwork: Network? = null
    @Volatile private var networkReady = false
    @Volatile private var proberStarted = false
    /** Wi‑Fi Direct path: no [Network], so the prober binds/probes with these overrides. */
    @Volatile private var p2pBindIp: Inet4Address? = null
    @Volatile private var p2pGatewayIp: Inet4Address? = null

    @Volatile private var lastFlowStartMs = 0L

    /**
     * Claim the start of an Android Auto flow. False when one began moments ago: a second start
     * tears down the session the first just opened, and that can wedge Android Auto's server.
     */
    @Synchronized
    fun claimFlowStart(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastFlowStartMs > 0 && now - lastFlowStartMs < FLOW_START_GUARD_MS) return false
        lastFlowStartMs = now
        return true
    }

    private const val FLOW_START_GUARD_MS = 3_000L

    /** Reset the gate at the start of a fresh Android Auto connection attempt. */
    @Synchronized
    fun beginHandoff() {
        aaVideoSteady = false
        bikeNetwork = null
        networkReady = false
        proberStarted = false
        p2pBindIp = null
        p2pGatewayIp = null
    }

    @Synchronized
    fun markAaVideoSteady() {
        aaVideoSteady = true
        maybeStartProbe()
    }

    /** [network] may be null on some devices (process already bound); readiness is the real signal. */
    @Synchronized
    fun markWifiReady(network: Network?) {
        bikeNetwork = network
        networkReady = true
        maybeStartProbe()
    }

    /** Wi‑Fi Direct: no [Network] — store bind/gateway IPs and mark ready. */
    @Synchronized
    fun markP2pReady(bindIp: Inet4Address, gatewayIp: Inet4Address) {
        p2pBindIp = bindIp
        p2pGatewayIp = gatewayIp
        bikeNetwork = null
        networkReady = true
        maybeStartProbe()
    }

    private fun maybeStartProbe() {
        if (proberStarted || !aaVideoSteady || !networkReady) return
        val p = prober ?: return
        proberStarted = true
        LogBus.log("→ AA video + bike Wi-Fi both ready — starting EasyConn PXC flow …")
        ConnectionState.set(Phase.PXC_CONNECTING)
        try {
            p.start(bikeNetwork, gatewayOverride = p2pGatewayIp, bindIpOverride = p2pBindIp)
        } catch (e: Exception) {
            LogBus.log("prober start failed: $e")
            ConnectionState.set(Phase.ERROR, "prober start failed")
        }
    }

    /**
     * The bike's Wi-Fi came back after a drop (e.g. the rider stopped and restarted the bike). The
     * old prober was probing a dead interface with stale IPs/server binds, so fully restart it on the
     * fresh [network]: stop it (this only closes sockets/servers — the shared Android Auto pipeline is
     * owned by [AndroidAutoService] and survives), then start again so it rebinds and re-probes. Called
     * by [BikeWifi] on every re-acquisition after the first.
     */
    @Synchronized
    fun onWifiReacquired(network: Network?) {
        // If the service parked Android Auto (long outage → torn down to save battery), it must rebuild
        // AA before the bike link is useful — hand off to the service instead of restarting the prober
        // against a dead (stopped) pipeline.
        if (AndroidAutoService.isParked) {
            LogBus.log("→ Wi-Fi back while AA parked — asking service to resume")
            AndroidAutoService.requestResume()
            return
        }
        val p = prober ?: return
        LogBus.log("→ restarting bike link on re-acquired Wi-Fi")
        ConnectionState.set(Phase.PXC_CONNECTING, "reconnecting")
        try { p.stop() } catch (_: Exception) {}
        bikeNetwork = network
        networkReady = true
        proberStarted = true
        try {
            p.start(network)
        } catch (e: Exception) {
            LogBus.log("prober restart failed: $e")
            ConnectionState.set(Phase.ERROR, "prober restart failed")
        }
    }
}
