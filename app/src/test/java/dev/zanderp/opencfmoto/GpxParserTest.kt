package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpxParserTest {
    @Test
    fun parsesTrackPointsAndWaypoints() {
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.1">
              <trk><name>Test Ride</name>
                <trkseg>
                  <trkpt lat="45.0" lon="25.0"><ele>100</ele></trkpt>
                  <trkpt lat="45.1" lon="25.1"/>
                </trkseg>
              </trk>
              <wpt lat="45.05" lon="25.05"><name>Peak</name></wpt>
            </gpx>
        """.trimIndent()
        val f = File.createTempFile("test", ".gpx")
        f.writeText(xml)
        val t = GpxParser.parse(f)
        f.delete()
        assertEquals("Test Ride", t.name)
        assertEquals(2, t.points.size)
        assertEquals(45.0, t.points[0].lat, 1e-6)
        assertEquals(1, t.waypoints.size)
        assertEquals("Peak", t.waypoints[0].name)
        assertTrue(t.points[0].ele == 100.0)
    }
}
