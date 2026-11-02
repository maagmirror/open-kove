// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Lightweight GPS-track thumbnail for trip list cards (no map tiles). */
object TripRouteThumb {
    fun render(
        trip: Trip,
        widthPx: Int,
        heightPx: Int,
        strokeColor: Int,
        bgColor: Int,
        startColor: Int,
        endColor: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgColor)

        val pts = subsample(trip.points, 180)
        if (pts.size < 2) return bmp

        var minLat = pts[0].lat
        var maxLat = pts[0].lat
        var minLon = pts[0].lon
        var maxLon = pts[0].lon
        for (p in pts) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon)
            maxLon = max(maxLon, p.lon)
        }
        // Equirectangular with cos(lat) so E–W isn’t stretched at mid latitudes.
        val midLat = (minLat + maxLat) * 0.5
        val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        var spanX = (maxLon - minLon) * cosLat
        var spanY = maxLat - minLat
        if (spanX < 1e-7) spanX = 1e-7
        if (spanY < 1e-7) spanY = 1e-7

        val pad = min(widthPx, heightPx) * 0.14f
        val drawW = widthPx - pad * 2
        val drawH = heightPx - pad * 2
        val scale = min(drawW / spanX, drawH / spanY)
        val usedW = spanX * scale
        val usedH = spanY * scale
        val ox = pad + (drawW - usedW) / 2f
        val oy = pad + (drawH - usedH) / 2f

        fun xOf(lon: Double) = (ox + ((lon - minLon) * cosLat) * scale).toFloat()
        fun yOf(lat: Double) = (oy + (maxLat - lat) * scale).toFloat()

        val path = Path()
        path.moveTo(xOf(pts[0].lon), yOf(pts[0].lat))
        for (i in 1 until pts.size) {
            path.lineTo(xOf(pts[i].lon), yOf(pts[i].lat))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = strokeColor
            strokeWidth = max(4f, min(widthPx, heightPx) * 0.035f)
        }
        canvas.drawPath(path, paint)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val r = max(5f, min(widthPx, heightPx) * 0.045f)
        dot.color = startColor
        canvas.drawCircle(xOf(pts.first().lon), yOf(pts.first().lat), r, dot)
        dot.color = endColor
        canvas.drawCircle(xOf(pts.last().lon), yOf(pts.last().lat), r, dot)

        return bmp
    }

    private fun subsample(points: List<TrackPoint>, maxPts: Int): List<TrackPoint> {
        if (points.size <= maxPts) return points
        val out = ArrayList<TrackPoint>(maxPts)
        val step = (points.size - 1).toDouble() / (maxPts - 1)
        var i = 0
        while (i < maxPts) {
            out.add(points[(i * step).toInt().coerceIn(0, points.lastIndex)])
            i++
        }
        return out
    }
}
