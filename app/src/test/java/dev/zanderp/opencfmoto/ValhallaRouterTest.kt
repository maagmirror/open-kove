// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ValhallaRouterTest {
    @Test
    fun decodePolyline6_roundTrip() {
        val encoded = encodePolyline6(listOf(44.4268 to 26.1025, 45.7489 to 21.2087))
        val pts = ValhallaRouter.decodePolyline6(encoded)
        assertEquals(2, pts.size)
        assertTrue(abs(pts[0].lat - 44.4268) < 1e-5)
        assertTrue(abs(pts[0].lon - 26.1025) < 1e-5)
        assertTrue(abs(pts[1].lat - 45.7489) < 1e-5)
        assertTrue(abs(pts[1].lon - 21.2087) < 1e-5)
    }

    @Test
    fun decodePolyline6_empty() {
        assertTrue(ValhallaRouter.decodePolyline6("").isEmpty())
    }

    private fun encodePolyline6(points: List<Pair<Double, Double>>): String {
        var prevLat = 0
        var prevLon = 0
        val sb = StringBuilder()
        for ((lat, lon) in points) {
            val iLat = Math.round(lat * 1e6).toInt()
            val iLon = Math.round(lon * 1e6).toInt()
            encodeSigned(iLat - prevLat, sb)
            encodeSigned(iLon - prevLon, sb)
            prevLat = iLat
            prevLon = iLon
        }
        return sb.toString()
    }

    private fun encodeSigned(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }
}
