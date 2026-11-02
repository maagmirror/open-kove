// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONArray
import java.io.File
import kotlin.math.abs

/**
 * Offline POI from a region pack (`pois.json`) or in-memory corridor cache.
 * Falls back to Nominatim / Overpass when empty.
 */
object OfflinePoiIndex {
    private var corridorCache: List<MapPlace> = emptyList()

    fun setCorridorCache(places: List<MapPlace>) {
        corridorCache = places
    }

    fun clearCorridorCache() {
        corridorCache = emptyList()
    }

    fun search(
        ctx: Context,
        query: String,
        nearLat: Double?,
        nearLon: Double?,
        limit: Int = 20,
    ): List<MapPlace> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = loadPackPois(ctx) + corridorCache
        if (all.isEmpty()) return emptyList()
        val scored = all.mapNotNull { p ->
            val name = p.name.lowercase()
            val cat = p.category.lowercase()
            val hit = name.contains(q) || cat.contains(q) || p.subtitle.lowercase().contains(q)
            if (!hit) return@mapNotNull null
            val dist = if (nearLat != null && nearLon != null) {
                abs(p.lat - nearLat) + abs(p.lon - nearLon)
            } else 0.0
            p to dist
        }
        return scored.sortedBy { it.second }.take(limit).map { it.first }
    }

    fun nearbyCategory(
        ctx: Context,
        categoryHint: String,
        lat: Double,
        lon: Double,
        limit: Int = 20,
    ): List<MapPlace> {
        val hint = categoryHint.lowercase()
        val all = loadPackPois(ctx) + corridorCache
        return all
            .filter {
                it.category.lowercase().contains(hint) ||
                    it.name.lowercase().contains(hint) ||
                    hint in it.subtitle.lowercase()
            }
            .sortedBy { abs(it.lat - lat) + abs(it.lon - lon) }
            .take(limit)
    }

    private fun loadPackPois(ctx: Context): List<MapPlace> {
        val id = RegionPackStore.activePackId(ctx) ?: return emptyList()
        val f = File(RegionPackStore.packDir(ctx, id), "pois.json")
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        MapPlace(
                            name = o.optString("name"),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon"),
                            category = o.optString("category"),
                            subtitle = o.optString("subtitle"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
