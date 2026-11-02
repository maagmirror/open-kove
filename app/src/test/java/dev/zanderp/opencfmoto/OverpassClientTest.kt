// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverpassClientTest {
    @Test
    fun parseMaxspeedKmh() {
        assertEquals(50, OverpassClient.parseMaxspeedKmh("50"))
        assertEquals(50, OverpassClient.parseMaxspeedKmh("50 km/h"))
        assertEquals(80, OverpassClient.parseMaxspeedKmh("50 mph"))
        assertNull(OverpassClient.parseMaxspeedKmh("none"))
    }

    @Test
    fun formatLimitUnits() {
        val lim = OverpassClient.SpeedLimit(100, "100")
        assertEquals("100", OverpassClient.formatLimit(lim, MapUnits.METRIC))
        assertEquals("62", OverpassClient.formatLimit(lim, MapUnits.IMPERIAL))
    }
}
