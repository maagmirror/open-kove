// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Worldwide place search / autocomplete for Map.
 * Uses Photon (location-biased) + settlement query so short prefixes prefer major
 * nearby cities over tiny locals / far-away name collisions. No region hardcoding.
 */
object NominatimSearch {
    data class PoiChip(val label: String, val query: String)

    val POI_CHIPS = listOf(
        PoiChip("Fuel", "amenity=fuel"),
        PoiChip("Cafe", "amenity=cafe"),
        PoiChip("Food", "amenity=restaurant"),
        PoiChip("Parking", "amenity=parking"),
        PoiChip("Hotel", "tourism=hotel"),
        PoiChip("ATM", "amenity=atm"),
        PoiChip("Hospital", "amenity=hospital"),
        PoiChip("Viewpoint", "tourism=viewpoint"),
    )

    fun searchAsync(
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
        onResult: (List<MapPlace>) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "place-search") {
            try {
                onResult(search(query, nearLat, nearLon))
            } catch (e: Exception) {
                onError(e.message ?: "Search failed")
            }
        }
    }

    fun search(query: String, nearLat: Double? = null, nearLon: Double? = null): List<MapPlace> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val cacheKey = cacheKey(q, nearLat, nearLon)
        cacheGet(cacheKey)?.let { return it }

        val result = if (nearLat != null && nearLon != null) {
            val photon = runCatching { searchPhotonBiased(q, nearLat, nearLon) }.getOrElse { err ->
                LogBus.log("[search] Photon failed: $err")
                emptyList()
            }
            if (photon.isNotEmpty()) photon
            else rankScored(searchNominatim(q, nearLat, nearLon), q, nearLat, nearLon)
        } else {
            rankScored(searchNominatim(q, nearLat, nearLon), q, nearLat, nearLon)
        }
        if (result.isNotEmpty()) cachePut(cacheKey, result)
        return result
    }

    // --- Tiny LRU cache so retyping / re-searching the same thing doesn't re-hit the network. ---
    private const val CACHE_MAX = 64
    private val cache = object : LinkedHashMap<String, List<MapPlace>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MapPlace>>) =
            size > CACHE_MAX
    }

    private fun cacheKey(q: String, lat: Double?, lon: Double?): String {
        val near = if (lat != null && lon != null) "%.2f,%.2f".format(lat, lon) else "-"
        return "${q.lowercase()}|$near"
    }

    private fun cacheGet(key: String): List<MapPlace>? = synchronized(cache) { cache[key] }
    private fun cachePut(key: String, value: List<MapPlace>) { synchronized(cache) { cache[key] = value } }

    /**
     * Two Photon queries, then one rank:
     * 1) general autocomplete (POIs / streets / everything near you)
     * 2) settlements only (city/town/municipality) so "Cons" → Constanța, not a metro stop
     */
    private fun searchPhotonBiased(query: String, nearLat: Double, nearLon: Double): List<MapPlace> {
        val general = fetchPhoton(query, nearLat, nearLon, limit = 12, settlementsOnly = false)
        val settlements = fetchPhoton(query, nearLat, nearLon, limit = 10, settlementsOnly = true)
        return rankScored(general + settlements, query, nearLat, nearLon)
    }

    private fun fetchPhoton(
        query: String,
        nearLat: Double,
        nearLon: Double,
        limit: Int,
        settlementsOnly: Boolean,
    ): List<ScoredPlace> {
        val enc = AppHttp.encode(query)
        val bias = if (settlementsOnly) 0.12 else 0.2
        var url =
            "https://photon.komoot.io/api/?q=$enc&lat=$nearLat&lon=$nearLon&limit=$limit" +
                "&location_bias_scale=$bias&lang=default"
        if (settlementsOnly) {
            url += "&osm_tag=place:city&osm_tag=place:town&osm_tag=place:municipality"
        }
        AppHttp.throttle("photon.komoot.io", 300)
        run {
            val root = JSONObject(AppHttp.getText(url))
            val features = root.optJSONArray("features") ?: JSONArray()
            return buildList {
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val geom = f.getJSONObject("geometry")
                    val coords = geom.getJSONArray("coordinates")
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val p = f.getJSONObject("properties")
                    val name = p.optString("name").ifBlank {
                        p.optString("street").ifBlank { "Place" }
                    }
                    val type = p.optString("osm_value").ifBlank { p.optString("type") }
                    add(
                        ScoredPlace(
                            place = MapPlace(
                                name = name,
                                lat = lat,
                                lon = lon,
                                category = type,
                                subtitle = buildPhotonSubtitle(p),
                            ),
                            type = type,
                            importance = extentImportance(p) + p.optDouble("importance", 0.0),
                            nameMatch = name,
                        ),
                    )
                }
            }
        }
    }

    private fun searchNominatim(
        query: String,
        nearLat: Double?,
        nearLon: Double?,
    ): List<ScoredPlace> {
        val enc = AppHttp.encode(query)
        var url =
            "https://nominatim.openstreetmap.org/search?q=$enc&format=jsonv2&addressdetails=1&limit=20"
        if (nearLat != null && nearLon != null) {
            // Soft regional preference (~180 km); bounded=0 still allows farther matches.
            val d = 1.6
            url += "&viewbox=${nearLon - d},${nearLat + d},${nearLon + d},${nearLat - d}&bounded=0"
        }
        // Nominatim usage policy: at most 1 request/second from a single source.
        AppHttp.throttle("nominatim.openstreetmap.org", 1_100)
        run {
            val arr = JSONArray(AppHttp.getText(url))
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val display = o.optString("display_name")
                    val name = o.optString("name").ifBlank {
                        display.substringBefore(',').ifBlank { "Place" }
                    }
                    val type = o.optString("type").ifBlank { o.optString("addresstype") }
                    add(
                        ScoredPlace(
                            place = MapPlace(
                                name = name,
                                lat = o.getDouble("lat"),
                                lon = o.getDouble("lon"),
                                category = type,
                                subtitle = display,
                            ),
                            type = type,
                            importance = o.optDouble("importance", 0.0),
                            nameMatch = name,
                        ),
                    )
                }
            }
        }
    }

    private fun rankScored(
        items: List<ScoredPlace>,
        query: String,
        nearLat: Double?,
        nearLon: Double?,
    ): List<MapPlace> {
        val q = query.trim().lowercase()
        return items
            .map { s ->
                val distKm = if (nearLat != null && nearLon != null) {
                    haversineKm(nearLat, nearLon, s.place.lat, s.place.lon)
                } else {
                    500.0
                }
                val typeW = typeWeight(s.type)
                val name = s.nameMatch.lowercase()
                val nameBonus = when {
                    name == q -> 40.0
                    name.startsWith(q) -> 28.0
                    name.contains(q) -> 10.0
                    else -> 0.0
                }
                // Soft distance: nearby wins ties; major cities a few hundred km away still beat
                // hamlets/POIs next door and same-name cities on other continents.
                val distPenalty = when {
                    distKm < 50 -> distKm * 0.12
                    distKm < 150 -> 6.0 + (distKm - 50) * 0.35
                    distKm < 500 -> 41.0 + (distKm - 150) * 0.55
                    distKm < 2000 -> 233.0 + (distKm - 500) * 0.35
                    else -> 758.0 + distKm * 0.15
                }
                val score = typeW * 12.0 + s.importance * 90.0 + nameBonus - distPenalty
                s.place to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { "${it.name.lowercase()}|${"%.3f".format(it.lat)}|${"%.3f".format(it.lon)}" }
            .take(12)
    }

    /** Place rank: city/municipality ≫ village/hamlet ≫ random POI. */
    private fun typeWeight(type: String): Double = when (type.lowercase()) {
        "city", "municipality" -> 100.0
        "town" -> 88.0
        "administrative", "county", "state", "region" -> 70.0
        "suburb", "borough", "quarter", "neighbourhood", "neighborhood" -> 45.0
        "village" -> 28.0
        "hamlet", "isolated_dwelling", "farm", "allotments" -> 8.0
        "locality" -> 22.0
        "highway", "road", "residential" -> 35.0
        "station", "bus_stop", "tram_stop", "subway_entrance" -> 12.0
        else -> 20.0
    }

    /** Larger Photon extent ≈ bigger settlement — useful worldwide importance proxy. */
    private fun extentImportance(p: JSONObject): Double {
        val ext = p.optJSONArray("extent") ?: return 0.0
        if (ext.length() < 4) return 0.0
        val w = abs(ext.getDouble(2) - ext.getDouble(0))
        val h = abs(ext.getDouble(1) - ext.getDouble(3))
        return min(0.9, w * h * 80.0)
    }

    private fun buildPhotonSubtitle(p: JSONObject): String {
        val parts = listOfNotNull(
            p.optString("city").takeIf { it.isNotBlank() },
            p.optString("county").takeIf { it.isNotBlank() },
            p.optString("state").takeIf { it.isNotBlank() },
            p.optString("country").takeIf { it.isNotBlank() },
        ).distinct()
        val type = p.optString("osm_value").takeIf { it.isNotBlank() }
        return buildString {
            if (type != null) append(type)
            if (parts.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(parts.joinToString(", "))
            }
        }.ifBlank { p.optString("country") }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private data class ScoredPlace(
        val place: MapPlace,
        val type: String,
        val importance: Double,
        val nameMatch: String,
    )
}
