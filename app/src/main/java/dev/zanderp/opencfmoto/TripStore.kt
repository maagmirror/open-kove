// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists recorded rides as one JSON file per trip under `filesDir/trips/`.
 *
 * A file per trip keeps writes atomic and cheap (no rewriting a growing master list), and makes
 * delete a single unlink. Each file holds the summary plus the full GPS track so the map can redraw
 * the route offline. Points are stored as compact `[lat, lon, timeMs, speedMs]` arrays.
 */
object TripStore {
    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "trips").apply { if (!exists()) mkdirs() }

    fun save(ctx: Context, trip: Trip) {
        val obj = JSONObject()
            .put("id", trip.id)
            .put("start", trip.start)
            .put("end", trip.end)
            .put("distanceMeters", trip.distanceMeters)
            .put("movingTimeMs", trip.movingTimeMs)
            .put("maxSpeedMs", trip.maxSpeedMs.toDouble())
        val pts = JSONArray()
        for (p in trip.points) {
            pts.put(JSONArray().put(p.lat).put(p.lon).put(p.time).put(p.speedMs.toDouble()))
        }
        obj.put("points", pts)
        runCatching { File(dir(ctx), "${trip.id}.json").writeText(obj.toString()) }
    }

    /** All saved trips, most recent first. */
    fun list(ctx: Context): List<Trip> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { parse(it) }.sortedByDescending { it.start }
    }

    fun get(ctx: Context, id: String): Trip? {
        val f = File(dir(ctx), "$id.json")
        return if (f.exists()) parse(f) else null
    }

    fun delete(ctx: Context, id: String) {
        runCatching { File(dir(ctx), "$id.json").delete() }
    }

    private fun parse(file: File): Trip? = runCatching {
        val o = JSONObject(file.readText())
        val ptsArr = o.optJSONArray("points") ?: JSONArray()
        val points = ArrayList<TrackPoint>(ptsArr.length())
        for (i in 0 until ptsArr.length()) {
            val a = ptsArr.optJSONArray(i) ?: continue
            points.add(
                TrackPoint(
                    lat = a.optDouble(0),
                    lon = a.optDouble(1),
                    time = a.optLong(2),
                    speedMs = a.optDouble(3).toFloat(),
                )
            )
        }
        Trip(
            id = o.optString("id"),
            start = o.optLong("start"),
            end = o.optLong("end"),
            distanceMeters = o.optDouble("distanceMeters"),
            movingTimeMs = o.optLong("movingTimeMs"),
            maxSpeedMs = o.optDouble("maxSpeedMs").toFloat(),
            points = points,
        )
    }.getOrNull()
}
