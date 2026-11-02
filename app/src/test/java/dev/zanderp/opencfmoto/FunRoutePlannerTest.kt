// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunRoutePlannerTest {
    @Test
    fun circuitWaypoints_closeLoop() {
        val vias = FunRoutePlanner.circuitWaypoints(45.0, 25.0, 60)
        assertEquals(4, vias.size)
        assertEquals(45.0, vias.last().lat, 1e-9)
        assertEquals(25.0, vias.last().lon, 1e-9)
        // Intermediate points should be away from the start.
        for (v in vias.dropLast(1)) {
            val km = FunRoutePlanner.haversineKm(45.0, 25.0, v.lat, v.lon)
            assertTrue(km > 3.0)
        }
    }

    @Test
    fun funViaSets_offerDetours() {
        val sets = FunRoutePlanner.funViaSets(45.0, 25.0, 46.0, 26.0)
        assertTrue(sets.size >= 2)
        assertTrue(sets.all { it.isNotEmpty() })
    }

    @Test
    fun twistScore_prefersLongerTwistier() {
        val short = OsrmRouter.Route(
            points = listOf(GpxPoint(0.0, 0.0), GpxPoint(0.1, 0.1)),
            distanceM = 10_000.0,
            durationSec = 600.0,
            steps = emptyList(),
        )
        val twisty = OsrmRouter.Route(
            points = listOf(GpxPoint(0.0, 0.0), GpxPoint(0.2, 0.2)),
            distanceM = 25_000.0,
            durationSec = 1_200.0,
            steps = List(20) {
                OsrmRouter.RouteStep("turn", "left", "Road", 0.0, 0.0, 100.0, null, null)
            },
        )
        assertTrue(FunRoutePlanner.twistScore(twisty) > FunRoutePlanner.twistScore(short))
    }
}
