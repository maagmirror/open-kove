package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripGpxTest {
    @Test
    fun writesTrackThatParserCanRead() {
        val trip = Trip(
            id = "1",
            start = 1_700_000_000_000L,
            end = 1_700_000_360_000L,
            distanceMeters = 12_500.0,
            movingTimeMs = 360_000L,
            maxSpeedMs = 25f,
            points = listOf(
                TrackPoint(45.0, 25.0, 1_700_000_000_000L, 10f),
                TrackPoint(45.01, 25.01, 1_700_000_180_000L, 12f),
            ),
        )
        val xml = TripGpx.toXml(trip)
        assertTrue(xml.contains("<gpx"))
        assertTrue(xml.contains("trkpt lat=\"45.000000\""))
        assertTrue(xml.contains("<time>"))

        val f = File.createTempFile("trip", ".gpx")
        f.writeText(xml)
        val parsed = GpxParser.parse(f)
        f.delete()
        assertEquals(2, parsed.points.size)
        assertEquals(45.0, parsed.points[0].lat, 1e-6)
        assertEquals(25.01, parsed.points[1].lon, 1e-6)
        assertTrue(TripGpx.suggestedFileName(trip).endsWith(".gpx"))
    }
}
