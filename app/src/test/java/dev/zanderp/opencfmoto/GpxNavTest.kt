// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxNavTest {
    @Test
    fun totalAndProgressAlongStraightTrack() {
        val pts = listOf(
            GpxPoint(0.0, 0.0),
            GpxPoint(0.01, 0.0),
            GpxPoint(0.02, 0.0),
        )
        val nav = GpxNav(GpxTrack("t", pts, emptyList()))
        assertTrue(nav.totalM in 2200.0..2300.0)
        val mid = nav.progress(0.01, 0.0, speedMps = 10f)!!
        assertEquals(1, mid.nearestIndex)
        assertTrue(mid.doneM in 1100.0..1150.0)
        assertTrue(mid.remainM in 1100.0..1150.0)
        assertTrue(mid.offTrackM < 5.0)
        assertNotNull(mid.etaSec)
        assertTrue(mid.etaSec!! in 100L..130L)
    }

    @Test
    fun etaUsesAssumedCruiseWhenStopped() {
        val pts = listOf(GpxPoint(0.0, 0.0), GpxPoint(0.01, 0.0))
        val nav = GpxNav(GpxTrack("t", pts, emptyList()))
        // Below crawl speed we still estimate ETA at ~50 km/h so the HUD never blanks.
        val eta = nav.progress(0.0, 0.0, speedMps = 0f)!!.etaSec
        assertNotNull(eta)
        assertTrue(eta!! > 60L)
    }

    @Test
    fun elevationAscentAndGrade() {
        val pts = listOf(
            GpxPoint(0.0, 0.0, ele = 100.0),
            GpxPoint(0.001, 0.0, ele = 110.0),
            GpxPoint(0.002, 0.0, ele = 130.0),
        )
        val nav = GpxNav(GpxTrack("hill", pts, emptyList()))
        assertTrue(nav.hasElevation)
        assertTrue(nav.totalAscentM in 29.0..31.0)
        val p = nav.progress(0.0, 0.0)!!
        assertNotNull(p.remainAscentM)
        assertTrue(p.remainAscentM!! >= 25.0)
        assertNotNull(p.gradePct)
        assertTrue(p.gradePct!! > 0)
    }

    @Test
    fun detectsRightTurnAhead() {
        val pts = ArrayList<GpxPoint>()
        for (i in 0..20) pts.add(GpxPoint(i * 0.001, 0.0))
        for (i in 1..20) pts.add(GpxPoint(0.02, i * 0.001))
        val nav = GpxNav(GpxTrack("corner", pts, emptyList()))
        val turn = nav.upcomingTurn(0, lookAheadM = 5000.0, minTurnDeg = 35f)
        assertNotNull(turn)
        assertTrue(
            turn!!.dir == GpxNav.TurnDir.RIGHT || turn.dir == GpxNav.TurnDir.SHARP_RIGHT,
        )
    }

    @Test
    fun zoomForSpeed() {
        // Tuned follow-zoom curve: closer at town speeds, still readable on the highway.
        assertEquals(17.0, GpxNav.zoomForSpeedKmh(10f), 0.01)
        assertEquals(13.8, GpxNav.zoomForSpeedKmh(80f), 0.01)
    }

    @Test
    fun formatKmAndEta() {
        assertEquals("500 m", GpxNav.formatKm(500.0))
        assertEquals("1.5 km", GpxNav.formatKm(1500.0))
        assertEquals("5m", GpxNav.formatEta(300L))
    }

    @Test
    fun formatImperialDistanceAndSpeed() {
        assertEquals("500 ft", GpxNav.formatDistance(152.4, MapUnits.IMPERIAL))
        assertEquals("1.0 mi", GpxNav.formatDistance(1609.344, MapUnits.IMPERIAL))
        assertEquals("62", GpxNav.formatSpeed(100f, MapUnits.IMPERIAL))
        assertEquals("mph", GpxNav.speedUnitLabel(MapUnits.IMPERIAL))
    }

    @Test
    fun dirToFaceUsesCompassRelativeToTarget() {
        // Facing north (0°), target east (90°) → turn right.
        assertEquals(GpxNav.TurnDir.RIGHT, GpxNav.dirToFace(0f, 90f))
        // Facing north, target west (270°) → turn left.
        assertEquals(GpxNav.TurnDir.LEFT, GpxNav.dirToFace(0f, 270f))
        // Already facing the target → go ahead.
        assertEquals(GpxNav.TurnDir.STRAIGHT, GpxNav.dirToFace(45f, 50f))
        // Facing opposite → turn around.
        assertEquals(GpxNav.TurnDir.UTURN, GpxNav.dirToFace(0f, 180f))
    }
}
