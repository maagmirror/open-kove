package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One GPS sample on a ride. */
data class TrackPoint(val lat: Double, val lon: Double, val time: Long, val speedMs: Float)

/**
 * A recorded ride: summary stats plus the full GPS track (for the map).
 *
 * Speeds are stored in m/s (as the GPS reports them); distance in metres; times in epoch millis.
 * The [id] is the start-time millis, which keeps files sortable and unique enough for one device.
 */
data class Trip(
    val id: String,
    val start: Long,
    val end: Long,
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val maxSpeedMs: Float,
    val points: List<TrackPoint>,
) {
    val distanceKm: Double get() = distanceMeters / 1000.0
    val maxKmh: Int get() = (maxSpeedMs * 3.6f).toInt()
    val avgKmh: Int get() =
        if (movingTimeMs > 0) ((distanceMeters / (movingTimeMs / 1000.0)) * 3.6).toInt() else 0

    fun durationText(): String {
        val sec = (movingTimeMs / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    fun distanceText(): String = String.format(Locale.US, "%.2f km", distanceKm)

    fun dateText(): String = DATE_FMT.format(Date(start))

    fun timeRangeText(): String {
        val s = TIME_FMT.format(Date(start))
        val e = TIME_FMT.format(Date(end))
        return "$s – $e"
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
