// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Waypoint helpers for Fun mode (twisty detours) and Fun circuits (loop rides).
 * Public OSRM has no "scenic" profile — we force character by routing through offset vias.
 */
object FunRoutePlanner {
    data class LatLon(val lat: Double, val lon: Double)

    /**
     * Several via-lists that pull the A→B path off the direct corridor (left/right, near/far).
     * Each list is inserted between start and end when calling the router.
     */
    fun funViaSets(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<List<LatLon>> {
        val distKm = haversineKm(fromLat, fromLon, toLat, toLon).coerceAtLeast(1.0)
        // Offset scales with trip length but stays rideable (3–18 km off-axis).
        val offsetKm = (distKm * 0.18).coerceIn(3.0, 18.0)
        val midL = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.45, sideKm = offsetKm)
        val midR = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.55, sideKm = -offsetKm)
        val earlyL = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.28, sideKm = offsetKm * 0.7)
        val lateR = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.72, sideKm = -offsetKm * 0.7)
        return listOf(
            listOf(midL),
            listOf(midR),
            listOf(earlyL, lateR),
        )
    }

    /**
     * Milder corridor offsets to force Google-style alternative A→B options when the router
     * only returns one geometry. Smaller than [funViaSets] so they stay "reasonable" alts.
     */
    fun altViaSets(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<List<LatLon>> {
        val distKm = haversineKm(fromLat, fromLon, toLat, toLon).coerceAtLeast(1.0)
        val offsetKm = (distKm * 0.10).coerceIn(1.5, 12.0)
        val midL = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.40, sideKm = offsetKm)
        val midR = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.60, sideKm = -offsetKm)
        val farL = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.50, sideKm = offsetKm * 1.6)
        return listOf(listOf(midL), listOf(midR), listOf(farL))
    }

    /**
     * Tiny left/right offsets for short hops so we can still offer Fast / Fun / Alt without
     * yanking the rider into a dead-end spur.
     */
    fun shortViaSets(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<List<LatLon>> {
        val distKm = haversineKm(fromLat, fromLon, toLat, toLon).coerceAtLeast(0.4)
        val offsetKm = (distKm * 0.14).coerceIn(0.35, 2.8)
        val midL = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.45, sideKm = offsetKm)
        val midR = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.55, sideKm = -offsetKm)
        val midL2 = offsetPoint(fromLat, fromLon, toLat, toLon, fraction = 0.35, sideKm = offsetKm * 1.35)
        return listOf(listOf(midL), listOf(midR), listOf(midL2))
    }

    /**
     * Closed loop around [lat],[lon] targeting roughly [targetKm] of riding.
     * Returns waypoints including the return to start (router coords = start + vias + start).
     */
    fun circuitWaypoints(lat: Double, lon: Double, targetKm: Int): List<LatLon> {
        val radiusKm = (targetKm / (2.0 * Math.PI)).coerceIn(4.0, 28.0)
        // Three bearings ≈ equilateral triangle around the rider.
        val bearings = doubleArrayOf(30.0, 150.0, 270.0)
        return bearings.map { brg -> destinationPoint(lat, lon, radiusKm, brg) } + LatLon(lat, lon)
    }

    /** Score higher for twistier rides (more distance per minute + more steps). */
    fun twistScore(route: OsrmRouter.Route): Double {
        val minutes = (route.durationSec / 60.0).coerceAtLeast(1.0)
        val km = route.distanceM / 1000.0
        val stepBonus = route.steps.size * 0.15
        return (km / minutes) * 8.0 + stepBonus + km * 0.05
    }

    fun offsetPoint(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        fraction: Double,
        sideKm: Double,
    ): LatLon {
        val lat = fromLat + (toLat - fromLat) * fraction
        val lon = fromLon + (toLon - fromLon) * fraction
        val brg = bearingDeg(fromLat, fromLon, toLat, toLon)
        return destinationPoint(lat, lon, kotlin.math.abs(sideKm), brg + if (sideKm >= 0) 90.0 else -90.0)
    }

    fun destinationPoint(lat: Double, lon: Double, distanceKm: Double, bearingDeg: Double): LatLon {
        val r = 6371.0
        val d = distanceKm / r
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asinSafe(sqrt(a))
    }

    private fun asinSafe(x: Double) = kotlin.math.asin(x.coerceIn(-1.0, 1.0))
}
