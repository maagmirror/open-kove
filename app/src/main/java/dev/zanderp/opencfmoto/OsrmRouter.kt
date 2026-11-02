// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Road-snapped navigate-to via the public OSRM demo server (driving profile).
 * Needs phone internet before / during link. Offline GraphHopper packs replace this when installed.
 *
 * Note: the public demo has no avoid-tolls / avoid-highway flags — those go through [OrsRouter].
 */
object OsrmRouter {
    /** One turn lane from OSRM intersections: which turns it allows + whether it's usable here. */
    data class Lane(val valid: Boolean, val indications: List<String>)

    /** A single guidance step: the maneuver to take and the road you travel afterwards. */
    data class RouteStep(
        val maneuverType: String,
        val modifier: String?,
        val roadName: String,
        val maneuverLat: Double,
        val maneuverLon: Double,
        val distanceM: Double,
        val exit: Int?,
        val lanes: List<Lane>?,
    )

    data class Route(
        val points: List<GpxPoint>,
        val distanceM: Double,
        val durationSec: Double,
        val steps: List<RouteStep> = emptyList(),
        /** Chip label: Fast / Fun / Alt / Circuit. */
        val label: String = "",
    ) {
        fun withLabel(label: String): Route = copy(label = label)
    }

    fun routeAsync(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        onResult: (Route) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "osrm") {
            try {
                onResult(route(fromLat, fromLon, toLat, toLon))
            } catch (e: Exception) {
                onError(e.message ?: "Routing failed")
            }
        }
    }

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route =
        routes(fromLat, fromLon, toLat, toLon, alternatives = 0).first()

    /**
     * One or several road routes through optional [vias] (lon/lat pairs between start and end).
     * [alternatives] is the OSRM count of *extra* options (3 → up to 4 total).
     */
    fun routes(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        alternatives: Int = 3,
        vias: List<FunRoutePlanner.LatLon> = emptyList(),
        label: String = "",
    ): List<Route> {
        val coords = buildString {
            append("$fromLon,$fromLat")
            for (v in vias) append(";${v.lon},${v.lat}")
            append(";$toLon,$toLat")
        }
        val altCount = alternatives.coerceIn(0, 4)
        // annotations=true balloons the JSON on long trips and often times out the public demo.
        val baseQuery = "?overview=full&geometries=geojson&steps=true"
        // Public OSRM demo server: keep requests spaced out so we stay a courteous guest.
        AppHttp.throttle("router.project-osrm.org", 600)
        AppHttp.ensureCellularUplink()
        if (!AppHttp.hasInternetPin()) {
            LogBus.log("[route] OSRM: no cellular/internet pin — request may fail on bike Wi‑Fi")
        }
        fun fetch(altParam: String, simplified: Boolean): List<Route> {
            // Long trips: full overview+steps often times out or exceeds body size on the public demo.
            val query = if (simplified) {
                "?overview=simplified&geometries=geojson&steps=false"
            } else {
                baseQuery
            }
            val url = "https://router.project-osrm.org/route/v1/driving/$coords$query$altParam"
            val root = JSONObject(
                AppHttp.getText(
                    url,
                    connectTimeoutMs = 25_000,
                    readTimeoutMs = if (simplified) 45_000 else 60_000,
                    maxBytes = AppHttp.MAX_ROUTE_BYTES,
                ),
            )
            if (root.optString("code") != "Ok") {
                error(root.optString("message", root.optString("code", "No route")))
            }
            val routes = root.getJSONArray("routes")
            if (routes.length() == 0) error("No route")
            val out = ArrayList<Route>(routes.length())
            for (i in 0 until routes.length()) {
                runCatching { parseRoute(routes.getJSONObject(i), label) }.getOrNull()?.let { out.add(it) }
            }
            if (out.isEmpty()) error("Route too short")
            return out
        }
        val altParam = if (altCount <= 0) "&alternatives=false" else "&alternatives=$altCount"
        return try {
            fetch(altParam, simplified = false)
        } catch (e: Exception) {
            // Long hauls frequently fail with alternatives — one solid road route is better than a beeline.
            if (altCount > 0) {
                LogBus.log("[route] OSRM alts failed (${e.message}) — retry single")
                try {
                    return fetch("&alternatives=false", simplified = false)
                } catch (e2: Exception) {
                    LogBus.log("[route] OSRM single failed (${e2.message}) — simplified fallback")
                    return fetch("&alternatives=false", simplified = true)
                }
            }
            LogBus.log("[route] OSRM full failed (${e.message}) — simplified fallback")
            fetch("&alternatives=false", simplified = true)
        }
    }

    private fun parseRoute(r0: JSONObject, label: String): Route {
        val coords = r0.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = buildList {
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                add(GpxPoint(lat = c.getDouble(1), lon = c.getDouble(0)))
            }
        }
        if (pts.size < 2) error("Route too short")
        return Route(
            points = pts,
            distanceM = r0.optDouble("distance", 0.0),
            durationSec = r0.optDouble("duration", 0.0),
            steps = parseSteps(r0),
            label = label,
        )
    }

    private fun parseSteps(route: JSONObject): List<RouteStep> {
        val out = ArrayList<RouteStep>()
        val legs = route.optJSONArray("legs") ?: return out
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val man = step.optJSONObject("maneuver") ?: continue
                val loc = man.optJSONArray("location")
                val lat = loc?.optDouble(1) ?: continue
                val lon = loc.optDouble(0)
                val exit = when {
                    man.has("exit") -> man.optInt("exit")
                    step.has("exit") -> step.optInt("exit")
                    else -> null
                }
                out.add(
                    RouteStep(
                        maneuverType = man.optString("type", "continue"),
                        modifier = man.optString("modifier").takeIf { it.isNotBlank() },
                        roadName = step.optString("name", ""),
                        maneuverLat = lat,
                        maneuverLon = lon,
                        distanceM = step.optDouble("distance", 0.0),
                        exit = exit,
                        lanes = parseLanes(step),
                    ),
                )
            }
        }
        return out
    }

    /** Turn lanes come from the step's intersections; use the entry intersection when present. */
    private fun parseLanes(step: JSONObject): List<Lane>? {
        val inters = step.optJSONArray("intersections") ?: return null
        for (i in 0 until inters.length()) {
            val lanesArr = inters.getJSONObject(i).optJSONArray("lanes") ?: continue
            val lanes = ArrayList<Lane>(lanesArr.length())
            for (j in 0 until lanesArr.length()) {
                val l = lanesArr.getJSONObject(j)
                val indArr = l.optJSONArray("indications")
                val inds = if (indArr != null) {
                    buildList { for (k in 0 until indArr.length()) add(indArr.optString(k)) }
                } else emptyList()
                lanes.add(Lane(valid = l.optBoolean("valid", false), indications = inds))
            }
            if (lanes.isNotEmpty()) return lanes
        }
        return null
    }

    fun toTrack(name: String, route: Route): GpxTrack =
        GpxTrack(name = name, points = route.points, waypoints = emptyList())
}
