// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Keeps the shared [TripRecorder] aligned with whatever ride surface is live:
 *  - Android Auto projection ([Phase.STREAMING]), and/or
 *  - the built-in map UI on the dash / phone ([mapUiLive]).
 *
 * Same store, same min-distance filter, same idle split as a projected AA ride — so Map / GPX / Nav
 * journeys show up next to AA rides in Saved trips. Honour Setup ▸ *Log trips automatically*.
 */
object TripAutoLog {
    /** True while [GpxDashUi] is bound (Presentation or in-app map). Cleared on [GpxDashUi.release]. */
    @Volatile var mapUiLive: Boolean = false
        private set

    fun setMapUiLive(context: Context, live: Boolean) {
        mapUiLive = live
        sync(context)
    }

    /**
     * Start recording when a ride surface is up; stop+save when none are. Safe to call often
     * (AA watchdog tick, map bind/release, session teardown).
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!AppSettings.logTrips(app)) {
            val rec = TripLogger.current
            if (rec != null && rec.recording) rec.stopAndSave()
            return
        }
        val shouldRecord =
            ConnectionState.phase == Phase.STREAMING || mapUiLive
        val rec = TripLogger.get(app)
        if (shouldRecord) {
            if (!rec.recording && rec.start()) {
                val via = when {
                    ConnectionState.phase == Phase.STREAMING && mapUiLive -> "AA + map"
                    mapUiLive -> "map"
                    else -> "AA"
                }
                LogBus.log("[trip] auto-logging this ride ($via)")
            }
        } else if (rec.recording) {
            rec.stopAndSave()
        }
    }
}
