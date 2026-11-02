// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import java.io.File

/**
 * osmdroid setup for the bike Presentation map (raster / Canvas — MapLibre GL blanks on the
 * VirtualDisplay).
 *
 * Tiles are fetched interactively as you pan and cached on disk (normal, policy-compliant use
 * with an identifying User-Agent). We do NOT bulk pre-download OSM raster tiles: OSM's tile
 * usage policy forbids it. Google-style "download this area for offline" is handled by the
 * MapLibre vector regions (OpenFreeMap) in [MapOfflineManager], which is built for that.
 *
 * While projecting, the process is bound to bike Wi‑Fi (no internet). Tile HTTP is pinned to
 * cellular via [CellularTileDownloader] / [AppHttp.openUrl].
 */
object GpxOsmdroid {
    // Zoom range used by the (compliant, vector) area download UI.
    const val AREA_ZOOM_MIN = 10
    const val AREA_ZOOM_STANDARD_MAX = 15
    const val AREA_ZOOM_HIGH_MAX = 16

    fun configure(context: Context) {
        val app = context.applicationContext
        val base = File(app.filesDir, "osmdroid")
        val cache = File(base, "tiles")
        base.mkdirs()
        cache.mkdirs()
        val cfg = Configuration.getInstance()
        // OSM tile policy requires an identifying User-Agent (the osmdroid default is blocked).
        cfg.userAgentValue = AppHttp.USER_AGENT
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = cache
        cfg.expirationOverrideDuration = 90L * 24 * 60 * 60 * 1000
        cfg.tileDownloadThreads = 2
        // Interactive tile cache ceiling before osmdroid trims oldest tiles.
        cfg.tileFileSystemCacheMaxBytes = 512L * 1024 * 1024
    }

    /**
     * Tile provider whose online downloads use [AppHttp] (cellular pin while on bike Wi‑Fi).
     */
    fun cellularTileProvider(
        context: Context,
        tileSource: ITileSource = TileSourceFactory.MAPNIK,
    ): MapTileProviderBasic {
        return object : MapTileProviderBasic(context, tileSource) {
            override fun createDownloaderProvider(
                aNetworkAvailablityCheck: INetworkAvailablityCheck?,
                pTileSource: ITileSource?,
            ): MapTileDownloader {
                val downloader = super.createDownloaderProvider(aNetworkAvailablityCheck, pTileSource)
                downloader.setTileDownloader(CellularTileDownloader())
                return downloader
            }
        }
    }

    /** Build a [MapView] with cellular-pinned tile downloads. */
    fun createMapView(context: Context): MapView {
        configure(context)
        return MapView(context, cellularTileProvider(context))
    }

    /** Total bytes of the interactive raster tile cache on disk. */
    fun rasterCacheBytes(context: Context): Long {
        val cache = File(File(context.applicationContext.filesDir, "osmdroid"), "tiles")
        return dirSize(cache)
    }

    /** Wipe the interactive raster tile cache. */
    fun clearRasterCache(context: Context) {
        val cache = File(File(context.applicationContext.filesDir, "osmdroid"), "tiles")
        runCatching { cache.deleteRecursively(); cache.mkdirs() }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
