// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Primary offline engine: downloads MapLibre vector tiles + style resources for a chosen area so
 * the phone dash renders the vector / 3D map with no connection. Each named area is stored as one
 * or more MapLibre offline regions (day and/or night style). osmdroid raster ([GpxOsmdroid]) is the
 * backup and covers the bike Presentation.
 */
object MapOfflineManager {
    /** Keep strong refs so observers aren't GC'd mid-download. */
    private val activeRegions = mutableListOf<OfflineRegion>()

    data class AreaSummary(
        val name: String,
        val regionCount: Int,
        val sizeBytes: Long,
    )

    private fun manager(ctx: Context): OfflineManager {
        MapLibre.getInstance(ctx.applicationContext)
        val m = OfflineManager.getInstance(ctx.applicationContext)
        // OpenFreeMap areas at z<=16 can exceed the default 6k tile cap.
        runCatching { m.setOfflineMapboxTileCountLimit(200_000L) }
        return m
    }

    private fun metadata(name: String, styleUrl: String, night: Boolean): ByteArray =
        JSONObject()
            .put(KEY_NAME, name)
            .put(KEY_STYLE, styleUrl)
            .put(KEY_NIGHT, night)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun nameOf(region: OfflineRegion): String =
        runCatching {
            JSONObject(String(region.metadata, Charsets.UTF_8)).optString(KEY_NAME)
        }.getOrDefault("").ifBlank { "Area ${region.id}" }

    /**
     * Download [styles] (style URL + night flag) for [bounds] between the zoom range.
     * Progress is the aggregate 0..100 across all styles; [onDone] fires once all finish.
     */
    fun downloadArea(
        ctx: Context,
        name: String,
        bounds: LatLngBounds,
        minZoom: Double,
        maxZoom: Double,
        styles: List<Pair<String, Boolean>>,
        onProgress: (percent: Int, downloadedBytes: Long) -> Unit,
        onDone: (ok: Boolean, message: String) -> Unit,
    ) {
        if (styles.isEmpty()) {
            onDone(false, "No map style to download")
            return
        }
        val mgr = manager(ctx)
        val pixelRatio = ctx.resources.displayMetrics.density
        var index = 0
        var totalBytes = 0L

        fun downloadNext() {
            if (index >= styles.size) {
                onDone(true, "Offline area \"$name\" ready")
                return
            }
            val (styleUrl, night) = styles[index]
            val def = OfflineTilePyramidRegionDefinition(
                styleUrl, bounds, minZoom, maxZoom, pixelRatio,
            )
            mgr.createOfflineRegion(
                def,
                metadata(name, styleUrl, night),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(region: OfflineRegion) {
                        activeRegions.add(region)
                        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                val req = status.requiredResourceCount.coerceAtLeast(1)
                                val done = status.completedResourceCount
                                // MapLibre often reports more than required mid-download; clamp.
                                val stylePct = (done * 100 / req).toInt().coerceIn(0, 100)
                                val overall =
                                    ((index * 100 + stylePct) / styles.size).coerceIn(0, 100)
                                onProgress(overall, totalBytes + status.completedResourceSize)
                                if (status.isComplete) {
                                    totalBytes += status.completedResourceSize
                                    region.setObserver(null)
                                    activeRegions.remove(region)
                                    LogBus.log(
                                        "[MAP] offline region done: $name " +
                                            "(${if (night) "night" else "day"}) " +
                                            "${status.completedResourceSize / 1024}KB",
                                    )
                                    index++
                                    downloadNext()
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                region.setObserver(null)
                                activeRegions.remove(region)
                                LogBus.log("[MAP] offline region error: ${error.reason} ${error.message}")
                                onDone(false, "Offline download failed: ${error.message}")
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                region.setObserver(null)
                                activeRegions.remove(region)
                                onDone(
                                    false,
                                    "Area too large (tile limit $limit). Pick a smaller area or lower detail.",
                                )
                            }
                        })
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        LogBus.log("[MAP] offline create failed: $error")
                        onDone(false, "Offline download failed: $error")
                    }
                },
            )
        }
        downloadNext()
    }

    /** Aggregate downloaded regions by area name for the UI list. */
    fun listAreas(ctx: Context, onResult: (List<AreaSummary>) -> Unit) {
        val mgr = manager(ctx)
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions?.toList() ?: emptyList()
                if (regions.isEmpty()) {
                    onResult(emptyList())
                    return
                }
                val byName = LinkedHashMap<String, IntArray>() // name -> [count]
                val sizes = LinkedHashMap<String, Long>()
                var pending = regions.size
                for (r in regions) {
                    val n = nameOf(r)
                    byName.getOrPut(n) { intArrayOf(0) }[0]++
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null) {
                                sizes[n] = (sizes[n] ?: 0L) + status.completedResourceSize
                            }
                            if (--pending == 0) emit(byName, sizes, onResult)
                        }

                        override fun onError(error: String?) {
                            if (--pending == 0) emit(byName, sizes, onResult)
                        }
                    })
                }
            }

            override fun onError(error: String) {
                LogBus.log("[MAP] offline list failed: $error")
                onResult(emptyList())
            }
        })
    }

    private fun emit(
        counts: Map<String, IntArray>,
        sizes: Map<String, Long>,
        onResult: (List<AreaSummary>) -> Unit,
    ) {
        onResult(
            counts.map { (name, c) ->
                AreaSummary(name, c[0], sizes[name] ?: 0L)
            },
        )
    }

    /** Delete every region belonging to a named area. */
    fun deleteArea(ctx: Context, name: String, onDone: (ok: Boolean) -> Unit) {
        val mgr = manager(ctx)
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val match = offlineRegions?.filter { nameOf(it) == name } ?: emptyList()
                if (match.isEmpty()) {
                    onDone(true)
                    return
                }
                var pending = match.size
                var ok = true
                for (r in match) {
                    r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            if (--pending == 0) onDone(ok)
                        }

                        override fun onError(error: String) {
                            ok = false
                            LogBus.log("[MAP] offline delete failed: $error")
                            if (--pending == 0) onDone(ok)
                        }
                    })
                }
            }

            override fun onError(error: String) {
                onDone(false)
            }
        })
    }

    private const val KEY_NAME = "name"
    private const val KEY_STYLE = "style"
    private const val KEY_NIGHT = "night"
}
