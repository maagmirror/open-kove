package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Process-global holder for the single [TripRecorder], so the foreground service (auto-logging a
 * projected ride) and the [TripActivity] HUD share one recorder and never double-count a ride.
 * Mirrors the existing process-global style ([BikeLink], [AaVideoBridge]).
 */
object TripLogger {
    @Volatile private var instance: TripRecorder? = null

    fun get(context: Context): TripRecorder =
        instance ?: synchronized(this) {
            instance ?: TripRecorder(context.applicationContext).also { instance = it }
        }

    val current: TripRecorder? get() = instance
}
