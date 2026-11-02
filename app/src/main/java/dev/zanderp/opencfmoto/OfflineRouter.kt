// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Road routing for Map / GPX.
 *
 * Avoid highways/tolls: ORS (if key) → Valhalla OSM.de (no key, Google-like fastest non-hwy) →
 * offline pack → OSRM fallback with warning. OSRM alone cannot exclude motorways.
 */
object OfflineRouter {
    data class LaneHint(val label: String, val speak: String)

    /**
     * Result of a preview/nav route request. [avoidHonored] is false when the rider asked to avoid
     * something we couldn't apply (no ORS key / ORS error) — UI should warn.
     */
    data class RouteResult(
        val routes: List<OsrmRouter.Route>,
        val avoidHonored: Boolean = true,
        val warning: String? = null,
    )

    /** The rider's own ORS key if set, otherwise the app's bundled default (may be blank). */
    fun effectiveOrsKey(ctx: Context): String {
        val user = MapPrefs.orsApiKey(ctx).trim()
        return if (user.isNotEmpty()) user else BuildConfig.ORS_API_KEY.trim()
    }

    /** True when a downloaded area's offline routing graph covers the whole trip. */
    fun hasOfflineGraph(ctx: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Boolean =
        OfflineRoadGraph.hasGraphFor(ctx, fromLat, fromLon, toLat, toLon)

    fun routeAsync(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        onResult: (OsrmRouter.Route) -> Unit,
        @Suppress("UNUSED_PARAMETER") onError: (String) -> Unit,
    ) {
        kotlin.concurrent.thread(name = "route") {
            val result = computeRoutes(ctx, fromLat, fromLon, toLat, toLon, wantAlts = false)
            onResult(result.routes.first())
        }
    }

    fun routeAlternativesAsync(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        onResult: (List<OsrmRouter.Route>) -> Unit,
        @Suppress("UNUSED_PARAMETER") onError: (String) -> Unit,
    ) {
        routeAlternativesDetailedAsync(ctx, fromLat, fromLon, toLat, toLon, { onResult(it.routes) }, onError)
    }

    fun routeAlternativesDetailedAsync(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        onResult: (RouteResult) -> Unit,
        @Suppress("UNUSED_PARAMETER") onError: (String) -> Unit,
    ) {
        kotlin.concurrent.thread(name = "route-alts") {
            onResult(computeRoutes(ctx, fromLat, fromLon, toLat, toLon, wantAlts = true))
        }
    }

    /**
     * Circuit = **out and back**: go to [toLat]/[toLon], then return to the start.
     * Built as start → destination → start (via at the turnaround).
     */
    fun circuitAlternativesAsync(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        onResult: (List<OsrmRouter.Route>) -> Unit,
        onError: (String) -> Unit,
    ) {
        kotlin.concurrent.thread(name = "route-circuit") {
            try {
                val out = computeOutAndBack(ctx, fromLat, fromLon, toLat, toLon)
                if (out.routes.isEmpty()) {
                    onError(out.warning ?: "Couldn't build there-and-back circuit")
                } else {
                    LogBus.log(
                        "[route] circuit out-and-back ${out.routes.size} option(s)" +
                            (out.warning?.let { " warn=$it" } ?: ""),
                    )
                    onResult(out.routes)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Circuit failed")
            }
        }
    }

    /** start → destination → start. */
    private fun computeOutAndBack(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): RouteResult {
        val opts = MapPrefs.routeOptions(ctx)
        val orsKey = effectiveOrsKey(ctx)
        val dest = FunRoutePlanner.LatLon(toLat, toLon)
        val approxKm = FunRoutePlanner.haversineKm(fromLat, fromLon, toLat, toLon)
        LogBus.log(
            "[route] circuit out-and-back ~${(approxKm * 2).toInt()}km round-trip " +
                "avoids=${opts.wantsAvoids} orsKey=${orsKey.isNotEmpty()}",
        )

        val merged = ArrayList<OsrmRouter.Route>()
        var warning: String? = null
        var avoidHonored = !opts.wantsAvoids
        var usedAvoidEngine = false

        fun fetch(
            vias: List<FunRoutePlanner.LatLon>,
            label: String,
        ): List<OsrmRouter.Route> {
            if (opts.wantsAvoids && orsKey.isNotEmpty()) {
                val hit = runCatching {
                    OrsRouter.routes(
                        orsKey, fromLat, fromLon, fromLat, fromLon, opts,
                        vias = vias,
                        alternatives = false,
                        label = label,
                    )
                }.onFailure {
                    LogBus.log("[route] ORS out-and-back fail: ${it.message}")
                }.getOrDefault(emptyList())
                if (hit.isNotEmpty()) {
                    usedAvoidEngine = true
                    return hit
                }
            }
            if (opts.wantsAvoids) {
                val hit = runCatching {
                    ValhallaRouter.routes(
                        fromLat, fromLon, fromLat, fromLon, opts,
                        vias = vias,
                        alternatives = 0,
                        label = label,
                    )
                }.onFailure {
                    LogBus.log("[route] Valhalla out-and-back fail: ${it.message}")
                }.getOrDefault(emptyList())
                if (hit.isNotEmpty()) {
                    usedAvoidEngine = true
                    return hit
                }
            }
            return runCatching {
                OsrmRouter.routes(
                    fromLat, fromLon, fromLat, fromLon,
                    alternatives = 0,
                    vias = vias,
                    label = label,
                )
            }.onFailure {
                LogBus.log("[route] OSRM out-and-back fail: ${it.message}")
            }.getOrDefault(emptyList())
        }

        merged.addAll(fetch(listOf(dest), "Circuit"))

        // Circuit = one there-and-back route, no Alt chips.
        if (merged.isEmpty()) {
            val out = computeRoutes(ctx, fromLat, fromLon, toLat, toLon, wantAlts = false)
            val back = computeRoutes(ctx, toLat, toLon, fromLat, fromLon, wantAlts = false)
            val a = out.routes.firstOrNull()
            val b = back.routes.firstOrNull()
            if (a != null && b != null && a.points.size >= 2 && b.points.size >= 2) {
                warning = listOfNotNull(out.warning, back.warning).firstOrNull()
                avoidHonored = out.avoidHonored && back.avoidHonored
                merged.add(
                    OsrmRouter.Route(
                        points = a.points + b.points.drop(1),
                        distanceM = a.distanceM + b.distanceM,
                        durationSec = a.durationSec + b.durationSec,
                        steps = a.steps + b.steps,
                        label = "Circuit",
                    ),
                )
            } else {
                warning = out.warning ?: back.warning ?: "Couldn't build there-and-back circuit"
            }
        } else if (opts.wantsAvoids) {
            avoidHonored = usedAvoidEngine
            if (!usedAvoidEngine) {
                warning = "Couldn't avoid highways/tolls — circuit may use motorway"
            }
        }

        val one = dedupe(merged).firstOrNull()?.withLabel("Circuit")
        return RouteResult(
            if (one != null) listOf(one) else emptyList(),
            avoidHonored = avoidHonored,
            warning = warning,
        )
    }

    private fun computeRoutes(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        wantAlts: Boolean,
        forcedVias: List<FunRoutePlanner.LatLon> = emptyList(),
        forceLabel: String? = null,
        optsOverride: RouteOptions? = null,
    ): RouteResult {
        val opts = optsOverride ?: MapPrefs.routeOptions(ctx)
        val approxKm = FunRoutePlanner.haversineKm(fromLat, fromLon, toLat, toLon)
        val orsKey = effectiveOrsKey(ctx)
        LogBus.log(
            "[route] request ~${approxKm.toInt()}km mode=${opts.mode} alts=$wantAlts " +
                "avoids=${opts.wantsAvoids} orsKey=${orsKey.isNotEmpty()} vias=${forcedVias.size}",
        )

        // Offline graphs can't do alternatives or avoid flags — only use them for simple single routes.
        val allowOffline = !wantAlts && !opts.wantsAvoids && forcedVias.isEmpty()
        if (allowOffline) {
            val graph = runCatching {
                OfflineRoadGraph.forRoute(ctx, fromLat, fromLon, toLat, toLon)
            }.getOrNull()
            if (graph != null) {
                val offline = runCatching { graph.route(fromLat, fromLon, toLat, toLon) }.getOrNull()
                if (offline != null && offline.points.size >= 2) {
                    LogBus.log("[route] offline graph ${offline.points.size} pts")
                    return RouteResult(
                        listOf(
                            offline.withLabel(
                                forceLabel ?: if (opts.mode == RouteMode.FUN) "Fun" else "Fast",
                            ),
                        ),
                    )
                }
            }
        }

        // ——— Avoid highways / tolls ———
        if (opts.wantsAvoids) {
            // 1) ORS (strict avoid_features) when the rider pasted a key.
            if (orsKey.isNotEmpty()) {
                val avoided = routeWithAvoids(
                    orsKey, fromLat, fromLon, toLat, toLon, opts, wantAlts, forcedVias, forceLabel, approxKm,
                )
                if (avoided.isNotEmpty()) {
                    return RouteResult(
                        finalizeAvoid(avoided, forceLabel, wantAlts, approxKm, opts.mode),
                        avoidHonored = true,
                    )
                }
            }
            // 2) Valhalla public instance — no key; fastest corridor with use_highways/tolls=0.
            val valhalla = routeWithValhallaAvoid(
                fromLat, fromLon, toLat, toLon, opts, wantAlts, forcedVias, forceLabel, approxKm,
            )
            if (valhalla.isNotEmpty()) {
                LogBus.log("[route] Valhalla avoid OK — ${valhalla.size} option(s)")
                return RouteResult(
                    finalizeAvoid(valhalla, forceLabel, wantAlts, approxKm, opts.mode),
                    avoidHonored = true,
                )
            }
            // 3) Offline pack: skip motorway/trunk (tolls not in the graph).
            if (forcedVias.isEmpty() && opts.avoidHighways) {
                val offlineAvoid = tryOfflineAvoid(ctx, fromLat, fromLon, toLat, toLon, forceLabel)
                if (offlineAvoid != null) {
                    val warn = if (opts.avoidTolls) {
                        "Avoiding motorways offline — tolls may still appear"
                    } else {
                        null
                    }
                    LogBus.log("[route] offline avoid-highways OK")
                    return RouteResult(listOf(offlineAvoid), avoidHonored = !opts.avoidTolls, warning = warn)
                }
            }
            val msg = "Couldn't avoid highways/tolls online — showing normal (may use motorway)"
            LogBus.log("[route] $msg — OSRM fallback")
            // Last resort: Google-like fast OSRM (no Fun vias) so the map isn't weird.
            val fallback = cloudWithoutAvoids(
                fromLat, fromLon, toLat, toLon,
                opts.copy(mode = RouteMode.FAST, avoidTolls = false, avoidHighways = false),
                wantAlts = false,
                forcedVias = forcedVias,
                forceLabel = forceLabel ?: "Fast",
                orsKey = orsKey,
            )
            return RouteResult(fallback, avoidHonored = false, warning = msg)
        }

        // ——— Normal Fast / Fun (no avoids) ———
        val routes = cloudWithoutAvoids(
            fromLat, fromLon, toLat, toLon, opts, wantAlts, forcedVias, forceLabel, orsKey,
        )
        return RouteResult(routes, avoidHonored = true)
    }

    private fun tryOfflineAvoid(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        forceLabel: String?,
    ): OsrmRouter.Route? {
        val graph = runCatching {
            OfflineRoadGraph.forRoute(ctx, fromLat, fromLon, toLat, toLon)
        }.getOrNull() ?: return null
        return runCatching {
            graph.route(fromLat, fromLon, toLat, toLon, avoidHighways = true)
        }.getOrNull()?.takeIf { it.points.size >= 2 }?.withLabel(forceLabel ?: "No highway")
    }

    /** ORS with avoid_features; mild corridor alts only (no Fun scenic vias). */
    private fun routeWithAvoids(
        orsKey: String,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        opts: RouteOptions,
        wantAlts: Boolean,
        forcedVias: List<FunRoutePlanner.LatLon>,
        forceLabel: String?,
        approxKm: Double,
    ): List<OsrmRouter.Route> {
        val merged = ArrayList<OsrmRouter.Route>()
        val orsAlts = wantAlts && forcedVias.isEmpty() && approxKm < 90.0
        val primaryLabel = forceLabel ?: avoidLabel(opts)

        fun ors(
            alts: Boolean,
            vias: List<FunRoutePlanner.LatLon> = forcedVias,
            label: String = primaryLabel,
        ): List<OsrmRouter.Route> = runCatching {
            OrsRouter.routes(
                orsKey, fromLat, fromLon, toLat, toLon, opts,
                vias = vias,
                alternatives = alts,
                label = label,
            )
        }.onFailure {
            LogBus.log("[route] ORS avoid fail alts=$alts vias=${vias.size}: ${it.message}")
        }.getOrDefault(emptyList())

        merged.addAll(ors(orsAlts))
        if (merged.isEmpty()) merged.addAll(ors(alts = false))

        // Longer trips only — short via offsets often invent spur U-turns.
        if (wantAlts && forcedVias.isEmpty() && approxKm >= 50.0 && merged.isNotEmpty() && merged.size < 2) {
            val vias = FunRoutePlanner.altViaSets(fromLat, fromLon, toLat, toLon).firstOrNull()
            if (vias != null) merged.addAll(ors(alts = false, vias = vias, label = "Alt"))
        }

        if (merged.isNotEmpty()) {
            LogBus.log("[route] ORS avoid OK — ${merged.size} option(s)")
        }
        return merged
    }

    /** Valhalla motorcycle exclude_highways — works without an ORS key. */
    private fun routeWithValhallaAvoid(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        opts: RouteOptions,
        wantAlts: Boolean,
        forcedVias: List<FunRoutePlanner.LatLon>,
        forceLabel: String?,
        approxKm: Double,
    ): List<OsrmRouter.Route> {
        val label = forceLabel ?: avoidLabel(opts)
        val alts = when {
            !wantAlts || forcedVias.isNotEmpty() -> 0
            approxKm > 100.0 -> 3
            approxKm < 50.0 -> 1
            else -> 2
        }
        return runCatching {
            ValhallaRouter.routes(
                fromLat, fromLon, toLat, toLon, opts,
                vias = forcedVias,
                alternatives = alts,
                label = label,
            )
        }.onFailure {
            LogBus.log("[route] Valhalla avoid fail: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun avoidLabel(opts: RouteOptions): String = when {
        opts.avoidHighways && opts.avoidTolls -> "No hwy/toll"
        opts.avoidHighways -> "No highway"
        opts.avoidTolls -> "No toll"
        else -> "Fast"
    }

    /** Avoid previews still use Fast / Fun / Alt order from the active mode. */
    private fun finalizeAvoid(
        routes: List<OsrmRouter.Route>,
        forceLabel: String?,
        wantAlts: Boolean,
        approxKm: Double,
        mode: RouteMode,
    ): List<OsrmRouter.Route> {
        if (!wantAlts) {
            val best = dedupe(routes).minByOrNull { it.durationSec } ?: return emptyList()
            return listOf(best.withLabel(forceLabel ?: "Fast"))
        }
        val picked = pickPreviewRoutes(routes, approxKm, mode, forceLabel)
        LogBus.log(
            "[route] avoid ${picked.size} option(s): " +
                picked.joinToString { "${it.label}/${(it.distanceM / 1000).toInt()}km/${it.durationSec.toInt()}s" },
        )
        return picked
    }

    /**
     * Preview routing without avoid flags.
     *
     * Prefers Valhalla corridor alternates (real DN/DJ roads). Random lat/lon via offsets are a
     * last resort on longer trips only — and any spur / U-turn / street ping-pong is discarded.
     */
    private fun cloudWithoutAvoids(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        opts: RouteOptions,
        wantAlts: Boolean,
        forcedVias: List<FunRoutePlanner.LatLon>,
        forceLabel: String?,
        orsKey: String,
    ): List<OsrmRouter.Route> {
        val merged = ArrayList<OsrmRouter.Route>()
        val approxKm = FunRoutePlanner.haversineKm(fromLat, fromLon, toLat, toLon)
        val longTrip = approxKm > 100.0
        val shortTrip = approxKm < 50.0
        AppHttp.ensureCellularUplink()
        LogBus.log(
            "[route] uplink=" + when {
                AppHttp.isCellularPin() -> "cellular"
                AppHttp.hasInternetPin() -> "internet"
                else -> "NONE — enable mobile data"
            },
        )
        val osrmAlts = when {
            !wantAlts -> 0
            forcedVias.isNotEmpty() -> 0
            // Public OSRM often times out long-haul alternatives — ask for fewer.
            longTrip -> 1
            else -> 2
        }

        fun osrm(
            alts: Int,
            vias: List<FunRoutePlanner.LatLon> = forcedVias,
            label: String = forceLabel ?: "",
        ): List<OsrmRouter.Route> = runCatching {
            OsrmRouter.routes(
                fromLat, fromLon, toLat, toLon,
                alternatives = alts,
                vias = vias,
                label = label,
            )
        }.onFailure {
            LogBus.log("[route] OSRM fail alts=$alts vias=${vias.size}: ${it.message}")
        }.getOrDefault(emptyList())

        // Valhalla first — better local corridor diversity than OSRM via hacks.
        if (wantAlts && forcedVias.isEmpty()) {
            val vAlts = if (longTrip) 3 else 2
            runCatching {
                ValhallaRouter.routes(
                    fromLat, fromLon, toLat, toLon,
                    options = opts.copy(avoidTolls = false, avoidHighways = false),
                    alternatives = vAlts,
                    label = forceLabel ?: "Fast",
                )
            }.onFailure {
                LogBus.log("[route] Valhalla alts fail: ${it.message}")
            }.getOrDefault(emptyList()).let { merged.addAll(it) }
        }

        merged.addAll(osrm(osrmAlts))
        if (merged.isEmpty()) merged.addAll(osrm(0))

        if (merged.isEmpty() && orsKey.isNotEmpty()) {
            val noAvoid = opts.copy(avoidTolls = false, avoidHighways = false)
            runCatching {
                OrsRouter.routes(
                    orsKey, fromLat, fromLon, toLat, toLon, noAvoid,
                    vias = forcedVias,
                    alternatives = false,
                    label = forceLabel ?: "",
                )
            }.onFailure {
                LogBus.log("[route] ORS (no avoid) fail: ${it.message}")
            }.getOrNull()?.let { merged.addAll(it) }
        }

        // Extra corridors via offsets — keep ONLY if they don't have U-turns / cul-de-sac spurs.
        // Native Valhalla/OSRM alts are trusted; offset vias are the usual junk source.
        if (wantAlts && forcedVias.isEmpty() && merged.isNotEmpty()) {
            val need = if (longTrip) 5 else 3
            if (dedupe(merged, approxKm).size < need) {
                val viaSets = if (shortTrip) {
                    FunRoutePlanner.shortViaSets(fromLat, fromLon, toLat, toLon)
                } else {
                    buildList {
                        addAll(FunRoutePlanner.funViaSets(fromLat, fromLon, toLat, toLon).take(if (longTrip) 2 else 1))
                        addAll(FunRoutePlanner.altViaSets(fromLat, fromLon, toLat, toLon).take(if (longTrip) 2 else 1))
                    }
                }
                for (vias in viaSets) {
                    if (dedupe(merged, approxKm).size >= need + 1) break
                    for (r in osrm(0, vias, "Alt")) {
                        if (isViaJunk(r)) {
                            LogBus.log("[route] drop spur/via junk ${(r.distanceM / 1000).toInt()}km")
                        } else {
                            merged.add(r)
                        }
                    }
                }
            }
            LogBus.log("[route] candidates=${merged.size} after via fill")
        }

        val finalized = finalize(merged, opts, forceLabel, wantAlts, approxKm)
        if (finalized.isNotEmpty()) return finalized

        val pin = when {
            AppHttp.isCellularPin() -> "cellular"
            AppHttp.hasInternetPin() -> "internet"
            else -> "NONE"
        }
        LogBus.log(
            "[route] online failed — beeline fallback (~${approxKm.toInt()}km) uplink=$pin " +
                "(enable mobile data while projecting — bike Wi‑Fi has no internet)",
        )
        return listOf(beeline(fromLat, fromLon, toLat, toLon).withLabel(forceLabel ?: "Direct"))
    }

    private fun finalize(
        routes: List<OsrmRouter.Route>,
        opts: RouteOptions,
        forceLabel: String?,
        wantAlts: Boolean,
        approxKm: Double,
    ): List<OsrmRouter.Route> {
        if (!wantAlts) {
            val best = dedupe(routes, approxKm).minByOrNull { it.durationSec } ?: return emptyList()
            val tag = forceLabel
                ?: if (opts.mode == RouteMode.FUN) "Fun" else "Fast"
            return listOf(best.withLabel(tag))
        }
        val picked = pickPreviewRoutes(routes, approxKm, opts.mode, forceLabel)
        LogBus.log(
            "[route] ${picked.size} option(s): " +
                picked.joinToString { "${it.label}/${(it.distanceM / 1000).toInt()}km/${it.durationSec.toInt()}s" },
        )
        return picked
    }

    /**
     * Preview chips:
     * - Fast mode → Fast, Fun, Alt
     * - Fun mode → Fun, Fast, Alt
     * - ≤100 km → max 3; >100 km → up to 5 (extra Alts)
     */
    private fun pickPreviewRoutes(
        routes: List<OsrmRouter.Route>,
        approxKm: Double,
        mode: RouteMode,
        forceLabel: String?,
    ): List<OsrmRouter.Route> {
        val maxTotal = if (approxKm > 100.0) 5 else 3
        val distinct = dedupe(routes, approxKm).sortedBy { it.durationSec }
        if (distinct.isEmpty()) return emptyList()

        val fastRaw = distinct.first()
        val rest = distinct.filter { !sameCorridor(it, fastRaw, approxKm) }
        val funRaw = rest.maxByOrNull { FunRoutePlanner.twistScore(it) }
        val altRaw = rest
            .filter { funRaw == null || !sameCorridor(it, funRaw, approxKm) }
            .minByOrNull { it.durationSec }

        val fast = fastRaw.withLabel(
            when {
                !forceLabel.isNullOrBlank() && mode == RouteMode.FAST -> forceLabel
                else -> "Fast"
            },
        )
        val funR = funRaw?.withLabel(
            when {
                !forceLabel.isNullOrBlank() && mode == RouteMode.FUN -> forceLabel
                else -> "Fun"
            },
        )
        val alt = altRaw?.withLabel("Alt")

        val core = when (mode) {
            RouteMode.FUN -> listOfNotNull(funR, fast, alt)
            RouteMode.FAST -> listOfNotNull(fast, funR, alt)
        }

        if (approxKm <= 100.0 || core.size >= maxTotal) return core.take(maxTotal)

        val extras = rest
            .filter { r -> core.none { sameCorridor(it, r, approxKm) } }
            .sortedBy { it.durationSec }
            .take(maxTotal - core.size)
            .map { it.withLabel("Alt") }
        return (core + extras).take(maxTotal)
    }

    private fun sameCorridor(a: OsrmRouter.Route, b: OsrmRouter.Route, approxKm: Double): Boolean {
        // Distinct if either distance or time differs meaningfully.
        val distTol = if (approxKm < 50.0) 500.0 else 1_500.0
        val timeTol = if (approxKm < 50.0) 90.0 else 180.0
        return kotlin.math.abs(a.distanceM - b.distanceM) < distTol &&
            kotlin.math.abs(a.durationSec - b.durationSec) < timeTol
    }

    /** Offset-via junk only: U-turn, street ping-pong, or tight cul-de-sac spur. */
    private fun isViaJunk(route: OsrmRouter.Route): Boolean {
        if (hasMidRouteUturn(route)) return true
        if (hasRoadNamePingPong(route)) return true
        if (hasTightCulDeSacSpur(route.points)) return true
        return false
    }

    private fun hasMidRouteUturn(route: OsrmRouter.Route): Boolean {
        if (route.steps.size < 3) return false
        val mid = route.steps.subList(1, route.steps.lastIndex)
        return mid.any { step ->
            val mod = step.modifier?.lowercase().orEmpty()
            val type = step.maneuverType.lowercase()
            "uturn" in mod || "uturn" in type || "u-turn" in mod || "u-turn" in type
        }
    }

    /** Same local street comes back after a short detour → dead-end spur (not DN/DJ trunks). */
    private fun hasRoadNamePingPong(route: OsrmRouter.Route): Boolean {
        var along = 0.0
        val last = HashMap<String, Double>()
        for ((idx, step) in route.steps.withIndex()) {
            if (idx == 0 || idx == route.steps.lastIndex) {
                along += step.distanceM
                continue
            }
            val name = step.roadName.trim().lowercase()
            if (!isLocalStreetName(name)) {
                along += step.distanceM
                continue
            }
            val prev = last[name]
            if (prev != null) {
                val gap = along - prev
                if (gap in 40.0..500.0) return true
            }
            last[name] = along
            along += step.distanceM
        }
        return false
    }

    private fun isLocalStreetName(name: String): Boolean {
        if (name.length < 5 || name == "-" || name == "unnamed road") return false
        if (name.matches(Regex("""^(dn|dj|dr|a|e)\s*\d.*"""))) return false
        if (name.startsWith("autostrada") || name.startsWith("centura")) return false
        if (name.startsWith("bulevard") || name.startsWith("calea ")) return false
        return name.startsWith("strada") || name.startsWith("aleea") || name.startsWith("intrarea")
    }

    /**
     * Tight cul-de-sac: geometry returns within 25 m of an earlier point after only 60–450 m
     * of travel. Wider windows false-flag normal town weaving / parallel DN segments.
     */
    private fun hasTightCulDeSacSpur(points: List<GpxPoint>): Boolean {
        if (points.size < 10) return false
        val sample = ArrayList<GpxPoint>()
        val cum = ArrayList<Double>()
        var dist = 0.0
        var last: GpxPoint? = null
        for (p in points) {
            val prev = last
            if (prev != null) dist += haversineM(prev.lat, prev.lon, p.lat, p.lon)
            if (prev == null || dist - (cum.lastOrNull() ?: -1e9) >= 20.0) {
                sample.add(p)
                cum.add(dist)
            }
            last = p
        }
        if (sample.size < 8) return false
        for (i in sample.indices) {
            val di = cum[i]
            for (j in i - 1 downTo 0) {
                val gap = di - cum[j]
                if (gap < 60.0) continue
                if (gap > 450.0) break
                if (haversineM(sample[i].lat, sample[i].lon, sample[j].lat, sample[j].lon) < 25.0) {
                    return true
                }
            }
        }
        return false
    }

    private fun dedupe(
        routes: List<OsrmRouter.Route>,
        approxKm: Double = 100.0,
    ): List<OsrmRouter.Route> {
        val distTol = if (approxKm < 50.0) 200.0 else 800.0
        val timeTol = if (approxKm < 50.0) 40.0 else 120.0
        val out = ArrayList<OsrmRouter.Route>()
        for (r in routes) {
            val dup = out.any { existing ->
                kotlin.math.abs(existing.distanceM - r.distanceM) < distTol &&
                    kotlin.math.abs(existing.durationSec - r.durationSec) < timeTol
            }
            if (!dup) out.add(r)
        }
        return out
    }

    private fun beeline(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): OsrmRouter.Route {
        val distM = haversineM(fromLat, fromLon, toLat, toLon)
        val n = (distM / 2000.0).toInt().coerceIn(2, 200)
        val pts = (0..n).map { i ->
            val t = i.toDouble() / n
            GpxPoint(lat = fromLat + (toLat - fromLat) * t, lon = fromLon + (toLon - fromLon) * t)
        }
        val depart = OsrmRouter.RouteStep(
            maneuverType = "depart",
            modifier = null,
            roadName = "Head toward destination (offline)",
            maneuverLat = fromLat,
            maneuverLon = fromLon,
            distanceM = distM,
            exit = null,
            lanes = null,
        )
        val arrive = OsrmRouter.RouteStep(
            maneuverType = "arrive",
            modifier = null,
            roadName = "Destination",
            maneuverLat = toLat,
            maneuverLon = toLon,
            distanceM = 0.0,
            exit = null,
            lanes = null,
        )
        return OsrmRouter.Route(
            points = pts,
            distanceM = distM,
            durationSec = distM / 13.9,
            steps = listOf(depart, arrive),
        )
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    fun laneHintFromText(instruction: String): LaneHint? {
        val t = instruction.lowercase()
        return when {
            "left lane" in t || "keep left" in t ->
                LaneHint("← lane", "use the left lane")
            "right lane" in t || "keep right" in t ->
                LaneHint("lane →", "use the right lane")
            "middle lane" in t || "center lane" in t ->
                LaneHint("↑ lane", "use the middle lane")
            else -> null
        }
    }
}
