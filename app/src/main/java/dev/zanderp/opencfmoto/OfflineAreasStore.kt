// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of downloaded offline map areas (name + bbox + zoom detail). MapLibre vector tiles live
 * in [MapOfflineManager]'s region database and osmdroid raster tiles in the shared tile cache; this
 * just remembers what the rider downloaded so the hub can list and delete named areas.
 */
object OfflineAreasStore {
    data class Area(
        val name: String,
        val north: Double,
        val south: Double,
        val east: Double,
        val west: Double,
        val zoomMax: Int,
        val vector: Boolean,
        val raster: Boolean,
        val createdAt: Long,
    )

    fun list(ctx: Context): List<Area> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Area(
                            name = o.optString("name"),
                            north = o.getDouble("north"),
                            south = o.getDouble("south"),
                            east = o.getDouble("east"),
                            west = o.getDouble("west"),
                            zoomMax = o.optInt("zoomMax", GpxOsmdroid.AREA_ZOOM_STANDARD_MAX),
                            vector = o.optBoolean("vector", true),
                            raster = o.optBoolean("raster", true),
                            createdAt = o.optLong("createdAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, area: Area) {
        val current = list(ctx).filter { it.name != area.name } + area
        save(ctx, current)
    }

    fun remove(ctx: Context, name: String) {
        save(ctx, list(ctx).filter { it.name != name })
    }

    private fun save(ctx: Context, areas: List<Area>) {
        val arr = JSONArray()
        for (a in areas) {
            arr.put(
                JSONObject()
                    .put("name", a.name)
                    .put("north", a.north)
                    .put("south", a.south)
                    .put("east", a.east)
                    .put("west", a.west)
                    .put("zoomMax", a.zoomMax)
                    .put("vector", a.vector)
                    .put("raster", a.raster)
                    .put("createdAt", a.createdAt),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "opencfmoto_offline_areas"
    private const val KEY = "areas"
}
