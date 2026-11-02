// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenRouteService directions (driving-car). POST /geojson supports avoid tolls/highways,
 * alternative routes (trips under ~100 km), and round-trip circuits that return to the start.
 *
 * Requires a free API key — public OSRM cannot honor avoid flags.
 */
object OrsRouter {
    private const val BASE = "https://api.openrouteservice.org/v2/directions/driving-car/geojson"

    fun route(
        apiKey: String,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        options: RouteOptions = RouteOptions(),
        vias: List<FunRoutePlanner.LatLon> = emptyList(),
    ): OsrmRouter.Route =
        routes(apiKey, fromLat, fromLon, toLat, toLon, options, vias, alternatives = false).first()

    fun routes(
        apiKey: String,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        options: RouteOptions = RouteOptions(),
        vias: List<FunRoutePlanner.LatLon> = emptyList(),
        alternatives: Boolean = true,
        label: String = "",
    ): List<OsrmRouter.Route> {
        val coords = JSONArray().apply {
            put(JSONArray().put(fromLon).put(fromLat))
            for (v in vias) put(JSONArray().put(v.lon).put(v.lat))
            put(JSONArray().put(toLon).put(toLat))
        }
        val body = JSONObject().apply {
            put("coordinates", coords)
            put("instructions", true)
            put("geometry", true)
            put("elevation", false)
            put(
                "preference",
                when {
                    // Avoid mode = Google-like: fastest among allowed roads (not scenic shortest).
                    options.wantsAvoids -> "recommended"
                    options.mode == RouteMode.FUN -> "shortest"
                    else -> "recommended"
                },
            )
            if (alternatives && options.maxAlternatives > 0) {
                put(
                    "alternative_routes",
                    JSONObject()
                        .put("target_count", 3)
                        .put("weight_factor", 2.0)
                        .put("share_factor", 0.8),
                )
            }
            putAvoidOptions(this, options)
        }
        return try {
            postAndParse(apiKey, body, label)
        } catch (e: Exception) {
            if (alternatives) {
                LogBus.log("[route] ORS alts failed (${e.message}) — retry single")
                body.remove("alternative_routes")
                postAndParse(apiKey, body, label)
            } else {
                throw e
            }
        }
    }

    /**
     * Loop ride that **returns to the start**. Uses ORS `options.round_trip` (single coordinate).
     * Honors avoid highways/tolls when set on [options].
     */
    fun roundTrip(
        apiKey: String,
        lat: Double,
        lon: Double,
        lengthM: Int,
        options: RouteOptions = RouteOptions(),
        points: Int = 5,
        seed: Int = 1,
        label: String = "Circuit",
    ): OsrmRouter.Route {
        val body = JSONObject().apply {
            put("coordinates", JSONArray().put(JSONArray().put(lon).put(lat)))
            put("instructions", true)
            put("geometry", true)
            put("elevation", false)
            put("preference", if (options.mode == RouteMode.FUN) "shortest" else "recommended")
            val opt = JSONObject()
            opt.put(
                "round_trip",
                JSONObject()
                    .put("length", lengthM.coerceIn(5_000, 200_000))
                    .put("points", points.coerceIn(3, 8))
                    .put("seed", seed),
            )
            val avoid = JSONArray()
            if (options.avoidHighways) avoid.put("highways")
            if (options.avoidTolls) avoid.put("tollways")
            if (avoid.length() > 0) opt.put("avoid_features", avoid)
            put("options", opt)
        }
        LogBus.log(
            "[route] ORS round_trip ${lengthM}m avoids=${options.wantsAvoids} seed=$seed",
        )
        return postAndParse(apiKey, body, label).first()
    }

    private fun putAvoidOptions(body: JSONObject, options: RouteOptions) {
        val avoid = JSONArray()
        if (options.avoidHighways) avoid.put("highways")
        if (options.avoidTolls) avoid.put("tollways")
        if (avoid.length() == 0) return
        val opt = body.optJSONObject("options") ?: JSONObject().also { body.put("options", it) }
        opt.put("avoid_features", avoid)
        LogBus.log("[route] ORS avoid_features=$avoid")
    }

    private fun postAndParse(apiKey: String, body: JSONObject, label: String): List<OsrmRouter.Route> {
        AppHttp.throttle("api.openrouteservice.org", 400)
        val key = apiKey.trim()
        // ORS accepts raw key or Bearer; try raw first (docs default).
        val root = try {
            JSONObject(
                AppHttp.postJson(
                    BASE,
                    body.toString(),
                    connectTimeoutMs = 20_000,
                    readTimeoutMs = 35_000,
                    extraHeaders = mapOf("Authorization" to key),
                ),
            )
        } catch (e: Exception) {
            if (key.startsWith("Bearer ", ignoreCase = true)) throw e
            LogBus.log("[route] ORS auth retry with Bearer (${e.message})")
            JSONObject(
                AppHttp.postJson(
                    BASE,
                    body.toString(),
                    connectTimeoutMs = 20_000,
                    readTimeoutMs = 35_000,
                    extraHeaders = mapOf("Authorization" to "Bearer $key"),
                ),
            )
        }
        val features = root.optJSONArray("features")
        if (features == null || features.length() == 0) {
            val err = root.optJSONObject("error")?.optString("message")
            error(err?.ifBlank { "No route" } ?: "No route")
        }
        val out = ArrayList<OsrmRouter.Route>(features.length())
        for (i in 0 until features.length()) {
            runCatching { parseFeature(features.getJSONObject(i), label) }.getOrNull()?.let { out.add(it) }
        }
        if (out.isEmpty()) error("Route too short")
        return out
    }

    private fun parseFeature(f0: JSONObject, label: String): OsrmRouter.Route {
        val coords = f0.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = buildList {
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                add(GpxPoint(lat = c.getDouble(1), lon = c.getDouble(0)))
            }
        }
        if (pts.size < 2) error("Route too short")
        val props = f0.optJSONObject("properties")
        val summary = props?.optJSONObject("summary")
        return OsrmRouter.Route(
            points = pts,
            distanceM = summary?.optDouble("distance", 0.0) ?: 0.0,
            durationSec = summary?.optDouble("duration", 0.0) ?: 0.0,
            steps = parseSteps(props, pts),
            label = label,
        )
    }

    private fun parseSteps(props: JSONObject?, pts: List<GpxPoint>): List<OsrmRouter.RouteStep> {
        val segments = props?.optJSONArray("segments") ?: return emptyList()
        val out = ArrayList<OsrmRouter.RouteStep>()
        for (si in 0 until segments.length()) {
            val steps = segments.getJSONObject(si).optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val wp = step.optJSONArray("way_points")
                val idx = (wp?.optInt(0) ?: 0).coerceIn(0, pts.lastIndex)
                val at = pts[idx]
                val (type, modifier) = mapType(step.optInt("type", 6))
                out.add(
                    OsrmRouter.RouteStep(
                        maneuverType = type,
                        modifier = modifier,
                        roadName = step.optString("name", "").let { if (it == "-") "" else it },
                        maneuverLat = at.lat,
                        maneuverLon = at.lon,
                        distanceM = step.optDouble("distance", 0.0),
                        exit = if (step.has("exit_number")) step.optInt("exit_number") else null,
                        lanes = null,
                    ),
                )
            }
        }
        return out
    }

    private fun mapType(t: Int): Pair<String, String?> = when (t) {
        0 -> "turn" to "left"
        1 -> "turn" to "right"
        2 -> "turn" to "sharp left"
        3 -> "turn" to "sharp right"
        4 -> "turn" to "slight left"
        5 -> "turn" to "slight right"
        6 -> "continue" to "straight"
        7 -> "roundabout" to null
        8 -> "exit roundabout" to null
        9 -> "turn" to "uturn"
        10 -> "arrive" to null
        11 -> "depart" to null
        12 -> "turn" to "slight left"
        13 -> "turn" to "slight right"
        else -> "continue" to "straight"
    }
}
