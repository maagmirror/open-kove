// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.SharedPreferences

/**
 * Namespaces user settings to the currently selected bike, so every motorcycle in the garage keeps
 * its own screen-fit, resolution, power mode, video quality, handlebar-button mode, and button
 * mapping.
 *
 * A scoped value is stored under `"<base>#<bikeId>"`, where the id is derived from the bike's raw QR
 * ([BikeMemory.selected]). **Reads fall back to the un-scoped (global) key** when the selected bike
 * has no value yet: a rider's existing single-bike settings become the default for every bike until
 * they customize one — so nothing is lost on upgrade, and a freshly scanned bike inherits sensible
 * values. Writes always go to the scoped key (or the global key when no bike is selected).
 */
object BikeScope {

    /** Stable per-bike id for the selected bike, or null when none is selected (use global keys). */
    fun suffix(ctx: Context): String? =
        BikeMemory.selected(ctx)?.raw?.let { idFor(it) }

    fun idFor(raw: String): String = "b" + Integer.toHexString(raw.hashCode())

    fun getString(p: SharedPreferences, ctx: Context, base: String, def: String?): String? {
        suffix(ctx)?.let { val k = "$base#$it"; if (p.contains(k)) return p.getString(k, def) }
        return p.getString(base, def)
    }

    fun putString(p: SharedPreferences, ctx: Context, base: String, value: String) {
        p.edit().putString(scopedKey(ctx, base), value).apply()
    }

    fun getBoolean(p: SharedPreferences, ctx: Context, base: String, def: Boolean): Boolean {
        suffix(ctx)?.let { val k = "$base#$it"; if (p.contains(k)) return p.getBoolean(k, def) }
        return p.getBoolean(base, def)
    }

    fun putBoolean(p: SharedPreferences, ctx: Context, base: String, value: Boolean) {
        p.edit().putBoolean(scopedKey(ctx, base), value).apply()
    }

    fun getInt(p: SharedPreferences, ctx: Context, base: String, def: Int): Int {
        suffix(ctx)?.let { val k = "$base#$it"; if (p.contains(k)) return p.getInt(k, def) }
        return p.getInt(base, def)
    }

    fun putInt(p: SharedPreferences, ctx: Context, base: String, value: Int) {
        p.edit().putInt(scopedKey(ctx, base), value).apply()
    }

    /** Drop the selected bike's scoped value so the setting reverts to its fallback/default. */
    fun remove(p: SharedPreferences, ctx: Context, base: String) {
        p.edit().remove(scopedKey(ctx, base)).apply()
    }

    private fun scopedKey(ctx: Context, base: String): String =
        suffix(ctx)?.let { "$base#$it" } ?: base
}
