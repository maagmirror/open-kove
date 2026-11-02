// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Dormant hosted-pack layout (PMTiles basemap + optional GraphHopper graph + pois.json) for a
 * future self-hosted region pack. Live offline downloads use [MapOfflineManager] (MapLibre vector
 * regions) + [GpxOsmdroid] (raster), tracked by [OfflineAreasStore]. These helpers stay so
 * [OfflinePoiIndex] / [OfflineRouter] / [MapLibreDashController] can consume a hosted pack if one is
 * ever installed under [packDir].
 */
object RegionPackStore {
    private const val PREFS = "opencfmoto_map_packs"
    private const val KEY_ACTIVE = "active_pack"

    fun packsDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "map-packs").also { it.mkdirs() }

    fun packDir(ctx: Context, id: String): File = File(packsDir(ctx), id)

    fun isInstalled(ctx: Context, id: String): Boolean {
        val dir = packDir(ctx, id)
        return File(dir, "basemap.pmtiles").isFile && File(dir, "manifest.json").isFile
    }

    fun activePackId(ctx: Context): String? =
        prefs(ctx).getString(KEY_ACTIVE, null)?.takeIf { isInstalled(ctx, it) }

    fun setActivePack(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun localStyleUri(ctx: Context, night: Boolean): String? {
        val id = activePackId(ctx) ?: return null
        val name = if (night) "style-night.json" else "style-day.json"
        val f = File(packDir(ctx, id), name)
        return if (f.isFile) "file://${f.absolutePath}" else null
    }

    fun readManifest(ctx: Context, id: String): JSONObject? {
        val f = File(packDir(ctx, id), "manifest.json")
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
