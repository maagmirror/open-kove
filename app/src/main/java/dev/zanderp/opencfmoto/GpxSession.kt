// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File

/**
 * Process-global state for the built-in map → bike path (Presentation / own-content
 * [VideoPipeline], not MediaProjection). Supports free ride, GPX track, and navigate-to place.
 */
object GpxSession {
    enum class Mode { FREE_RIDE, GPX, NAV_TO }

    @Volatile var active: Boolean = false
        private set

    @Volatile var mode: Mode = Mode.FREE_RIDE
        private set

    @Volatile var trackFile: File? = null
        private set

    @Volatile var trackName: String = ""
        private set

    /** Destination for NAV_TO (and optional marker while on a GPX). */
    @Volatile var destination: MapPlace? = null
        private set

    /** Favorites/markers snapshot to draw on the dash (copied at prepare time). */
    @Volatile var overlayPlaces: List<MapPlace> = emptyList()
        private set

    @Volatile private var touchTarget: View? = null

    /** Hub asked the map to build a there-and-back circuit once a destination is set. */
    @Volatile var pendingCircuit: Boolean = false

    /** Wall-clock when [prepareNavTo] / in-dash Go was last committed (0 = not navigating). */
    @Volatile var navStartedAtMs: Long = 0L
        private set

    fun prepareFreeRide(overlays: List<MapPlace> = emptyList()) {
        mode = Mode.FREE_RIDE
        trackFile = null
        trackName = "Free ride"
        destination = null
        overlayPlaces = overlays
        navStartedAtMs = 0L
        active = true
    }

    fun prepareGpx(file: File, displayName: String, overlays: List<MapPlace> = emptyList()) {
        mode = Mode.GPX
        trackFile = file
        trackName = displayName
        destination = null
        overlayPlaces = overlays
        navStartedAtMs = 0L
        active = true
    }

    fun prepareNavTo(place: MapPlace, overlays: List<MapPlace> = emptyList()) {
        mode = Mode.NAV_TO
        trackFile = null
        trackName = place.name
        destination = place
        overlayPlaces = overlays
        navStartedAtMs = System.currentTimeMillis()
        active = true
    }

    fun clear() {
        active = false
        mode = Mode.FREE_RIDE
        trackFile = null
        trackName = ""
        destination = null
        overlayPlaces = emptyList()
        navStartedAtMs = 0L
        touchTarget = null
        pendingCircuit = false
    }

    /** Finish navigation — drop route, keep map as free ride. */
    fun finishToFreeRide() {
        if (!active) return
        val overlays = overlayPlaces
        prepareFreeRide(overlays)
    }

    /**
     * Drop a leftover NAV_TO that isn't being projected anymore (app reopen / killed journey).
     * Keeps a live free-ride / GPX session intact.
     */
    fun abandonStaleNavigation(reason: String = "stale") {
        if (!active || mode != Mode.NAV_TO) return
        LogBus.log("[GPX] abandon NAV_TO '${destination?.name}' ($reason) → free ride")
        finishToFreeRide()
    }

    fun setTouchTarget(view: View?) {
        touchTarget = view
    }

    fun clearTouchTarget() {
        touchTarget = null
    }

    fun dispatchTouch(action: Int, canvasX: Int, canvasY: Int): Boolean {
        val target = touchTarget ?: return false
        val motion = when (action) {
            0 -> MotionEvent.ACTION_DOWN
            1 -> MotionEvent.ACTION_UP
            2 -> MotionEvent.ACTION_MOVE
            else -> return false
        }
        val x = canvasX.toFloat()
        val y = canvasY.toFloat()
        target.post {
            val t = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(t, t, motion, x, y, 0)
            try {
                target.dispatchTouchEvent(ev)
            } finally {
                ev.recycle()
            }
        }
        return true
    }
}
