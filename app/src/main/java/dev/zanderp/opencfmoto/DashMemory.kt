// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Remembers the canvas size each dash asks for (`REQ_CONFIG_CAPTURE` width×height), keyed by SSID.
 *
 * On the next connect [specFor] / [BikeProfiles.selectByQr] can pick an AA resolution that fits the
 * panel at 1:1 (LearnedPanels-style), not just flip portrait/landscape.
 */
object DashMemory {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_PREFIX = "dash_geo_"
    private const val KEY_LAST_TOUCH = "last_dash_touch"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setLastDashTouch(ctx: Context, touch: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LAST_TOUCH, touch).apply()
    }

    fun lastDashTouch(ctx: Context): Boolean? {
        val p = prefs(ctx)
        return if (p.contains(KEY_LAST_TOUCH)) p.getBoolean(KEY_LAST_TOUCH, false) else null
    }

    fun observe(ctx: Context, ssid: String?, w: Int, h: Int) {
        if (ssid.isNullOrBlank() || w <= 0 || h <= 0) return
        val key = KEY_PREFIX + ssid
        val now = "${w}x$h"
        val prior = prefs(ctx).getString(key, null)
        prefs(ctx).edit().putString(key, now).apply()
        if (prior != now) {
            LogBus.log(
                "[panel] learned: this bike's screen is $now — next connect will fit AA " +
                    "resolution + match-aspect margins to it",
            )
        }
    }

    fun get(ctx: Context, ssid: String?): Pair<Int, Int>? {
        if (ssid.isNullOrBlank()) return null
        val v = prefs(ctx).getString(KEY_PREFIX + ssid, null) ?: return null
        val parts = v.split("x")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (w in 1..8192 && h in 1..8192) w to h else null
    }

    /**
     * Pick an AA resolution that can hold [w]×[h] at 1:1 on one axis (see open-cflink LearnedPanels).
     * Returns null when no standard size fits cleanly — caller keeps the profile guess.
     */
    fun bestFit(w: Int, h: Int): AaResolution? {
        val cw = w and 0xFFF0
        val ch = h and 0xFFF0
        val exact = AaResolution.entries.filter { r ->
            (r.w == cw && r.h >= ch) || (r.h == ch && r.w >= cw)
        }
        return exact.minByOrNull { (it.w - cw).toLong() * (it.h - ch) + (it.w - cw) + (it.h - ch) }
    }

    /**
     * AA spec from remembered geometry. Prefers exact [bestFit]; else orientation flip to SD sizes.
     */
    fun specFor(ctx: Context, ssid: String?, profile: BikeProfile): AaVideoSpec? {
        val (w, h) = get(ctx, ssid) ?: return null
        bestFit(w, h)?.let { res ->
            if (res == profile.aaVideo.resolution && profile.panelSize == w to h) return null
            return AaVideoSpec(res, dpi = profile.aaVideo.dpi)
        }
        val dashPortrait = h > w
        val profilePortrait = profile.aaVideo.height > profile.aaVideo.width
        if (dashPortrait == profilePortrait) return null
        return if (dashPortrait) AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)
        else AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)
    }
}
