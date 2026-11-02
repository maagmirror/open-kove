// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import org.json.JSONArray
import org.json.JSONObject

/**
 * Valhalla turn-by-turn via the public OSM.de instance — no API key.
 *
 * Google-like Avoid highways = ban motorways/autostradă, but still take the fastest DN/trunk
 * roads. We use motorcycle costing with `exclude_highways=true` for that hard ban.
 *
 * Do **not** use auto `use_highways=0` — that over-penalizes national roads and can invent
 * 8–10 h country detours when a ~4.5 h DN route exists.
 */
object ValhallaRouter {
    private const val ROUTE_URL = "https://valhalla1.openstreetmap.de/route"

    private data class ParsedTrip(
        val route: OsrmRouter.Route,
        val motorwayKm: Double,
        val tollKm: Double,
    )

    fun routes(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        options: RouteOptions = RouteOptions(),
        vias: List<FunRoutePlanner.LatLon> = emptyList(),
        alternatives: Int = 0,
        label: String = "",
    ): List<OsrmRouter.Route> {
        val locations = JSONArray().apply {
            put(loc(fromLat, fromLon))
            for (v in vias) put(loc(v.lat, v.lon))
            put(loc(toLat, toLon))
        }
        // Motorcycle + hard motorway exclude ≈ Google "Avoid highways" (DN ok, A2 out).
        // Auto + use_highways=0 is too harsh and often picks slow DJ roads.
        val profile = if (options.avoidHighways) "motorcycle" else "auto"
        val costing = JSONObject().apply {
            put("shortest", false)
            if (options.avoidHighways) put("exclude_highways", true)
            if (options.avoidTolls) {
                put("use_tolls", 0)
                put("toll_booth_penalty", 600)
            }
        }
        val body = JSONObject().apply {
            put("locations", locations)
            put("costing", profile)
            put("costing_options", JSONObject().put(profile, costing))
            put("directions_options", JSONObject().put("units", "kilometers"))
            val alt = alternatives.coerceIn(0, 3)
            if (alt > 0) put("alternates", alt)
        }
        LogBus.log(
            "[route] Valhalla profile=$profile avoidHwy=${options.avoidHighways} " +
                "avoidToll=${options.avoidTolls} alts=$alternatives vias=${vias.size}",
        )
        AppHttp.throttle("valhalla1.openstreetmap.de", 500)
        AppHttp.ensureCellularUplink()
        val root = JSONObject(
            AppHttp.postJson(
                ROUTE_URL,
                body.toString(),
                connectTimeoutMs = 25_000,
                readTimeoutMs = 60_000,
                maxBytes = AppHttp.MAX_ROUTE_BYTES,
            ),
        )
        val parsed = ArrayList<ParsedTrip>()
        root.optJSONObject("trip")?.let { trip ->
            parseTrip(trip, label.ifBlank { avoidLabel(options) })?.let { parsed.add(it) }
        }
        val alts = root.optJSONArray("alternates")
        if (alts != null) {
            for (i in 0 until alts.length()) {
                val trip = alts.optJSONObject(i)?.optJSONObject("trip") ?: continue
                parseTrip(trip, "Alt")?.let { parsed.add(it) }
            }
        }
        val filtered = parsed.filter { p ->
            val hwyOk = !options.avoidHighways || p.motorwayKm < 1.0
            val tollOk = !options.avoidTolls || p.tollKm < 1.0
            hwyOk && tollOk
        }.ifEmpty {
            // Keep best effort if every alternate still clipped a ramp.
            parsed
        }
        val out = filtered.sortedBy { it.route.durationSec }.map { it.route }
        if (out.isEmpty()) error("No Valhalla route")
        LogBus.log(
            "[route] Valhalla picked ${out.size}: " +
                out.joinToString {
                    val h = it.durationSec / 3600.0
                    "${it.label}/${(it.distanceM / 1000).toInt()}km/${"%.1f".format(java.util.Locale.US, h)}h"
                },
        )
        return out
    }

    private fun avoidLabel(options: RouteOptions): String = when {
        options.avoidHighways && options.avoidTolls -> "No hwy/toll"
        options.avoidHighways -> "No highway"
        options.avoidTolls -> "No toll"
        else -> "Fast"
    }

    private fun loc(lat: Double, lon: Double): JSONObject =
        JSONObject().put("lat", lat).put("lon", lon)

    private fun parseTrip(trip: JSONObject, label: String): ParsedTrip? {
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null
        val allPts = ArrayList<GpxPoint>()
        val allSteps = ArrayList<OsrmRouter.RouteStep>()
        var distKm = 0.0
        var timeSec = 0.0
        var motorwayKm = 0.0
        var tollKm = 0.0
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val shape = leg.optString("shape", "")
            val pts = decodePolyline6(shape)
            if (pts.size < 2) continue
            if (allPts.isEmpty()) allPts.addAll(pts) else allPts.addAll(pts.drop(1))
            val summary = leg.optJSONObject("summary")
            distKm += summary?.optDouble("length", 0.0) ?: 0.0
            timeSec += summary?.optDouble("time", 0.0) ?: 0.0
            val maneuvers = leg.optJSONArray("maneuvers") ?: continue
            for (mi in 0 until maneuvers.length()) {
                val m = maneuvers.getJSONObject(mi)
                val begin = m.optInt("begin_shape_index", 0)
                val at = pts.getOrNull(begin.coerceIn(0, pts.lastIndex)) ?: continue
                val names = m.optJSONArray("street_names")
                val road = buildString {
                    if (names != null) {
                        for (ni in 0 until names.length()) {
                            if (ni > 0) append('/')
                            append(names.optString(ni, ""))
                        }
                    }
                }
                val lenKm = m.optDouble("length", 0.0)
                if (m.optBoolean("highway", false) || looksLikeMotorway(road)) {
                    motorwayKm += lenKm
                }
                if (m.optBoolean("toll", false)) tollKm += lenKm
                val (type, modifier) = mapManeuver(m.optInt("type", 0))
                allSteps.add(
                    OsrmRouter.RouteStep(
                        maneuverType = type,
                        modifier = modifier,
                        roadName = road.substringBefore('/'),
                        maneuverLat = at.lat,
                        maneuverLon = at.lon,
                        distanceM = lenKm * 1000.0,
                        exit = null,
                        lanes = null,
                    ),
                )
            }
        }
        if (allPts.size < 2) return null
        val summary = trip.optJSONObject("summary")
        if (distKm <= 0.0) distKm = summary?.optDouble("length", 0.0) ?: 0.0
        if (timeSec <= 0.0) timeSec = summary?.optDouble("time", 0.0) ?: 0.0
        return ParsedTrip(
            route = OsrmRouter.Route(
                points = allPts,
                distanceM = distKm * 1000.0,
                durationSec = timeSec,
                steps = allSteps,
                label = label,
            ),
            motorwayKm = motorwayKm,
            tollKm = tollKm,
        )
    }

    /** Romanian/EU motorway name patterns (A2, Autostrada Soarelui, …). */
    private fun looksLikeMotorway(road: String): Boolean {
        if (road.isBlank()) return false
        val r = road.lowercase()
        if ("autostrada" in r || "autobahn" in r || "autoroute" in r) return true
        // Bare A12 / A 2 style refs — not "DN2A" / "DJ12A".
        return Regex("""(?<![a-z])a\s?\d{1,3}(?!\d*[a-z])""", RegexOption.IGNORE_CASE).containsMatchIn(road)
    }

    /**
     * Valhalla DirectionsLeg.Maneuver.Type → OSRM-ish type/modifier used by [GpxNav].
     * @see https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/
     */
    private fun mapManeuver(t: Int): Pair<String, String?> = when (t) {
        1, 2, 3 -> "depart" to null // Start / StartRight / StartLeft
        4, 5, 6 -> "arrive" to null // Destination*
        7, 8 -> "continue" to "straight" // Becomes / Continue
        9 -> "turn" to "slight right"
        10 -> "turn" to "right"
        11 -> "turn" to "sharp right"
        12 -> "turn" to "uturn" // UturnRight
        13 -> "turn" to "uturn" // UturnLeft
        14 -> "turn" to "sharp left"
        15 -> "turn" to "left"
        16 -> "turn" to "slight left"
        17 -> "on ramp" to "straight" // RampStraight
        18 -> "on ramp" to "right"
        19 -> "on ramp" to "left"
        20 -> "off ramp" to "right" // ExitRight
        21 -> "off ramp" to "left"
        22 -> "continue" to "straight" // StayStraight
        23 -> "fork" to "right" // StayRight
        24 -> "fork" to "left"
        25 -> "merge" to null
        26 -> "roundabout" to null // RoundaboutEnter
        27 -> "exit roundabout" to null
        28, 29 -> "ferry" to null
        else -> "continue" to "straight"
    }

    /** Google/Valhalla encoded polyline with 1e-6 precision. */
    fun decodePolyline6(encoded: String): List<GpxPoint> {
        if (encoded.isEmpty()) return emptyList()
        val out = ArrayList<GpxPoint>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            val dlon = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lon += dlon
            out.add(GpxPoint(lat = lat / 1e6, lon = lon / 1e6))
        }
        return out
    }
}
