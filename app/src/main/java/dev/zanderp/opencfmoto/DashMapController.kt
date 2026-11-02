// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reliable dash map for Presentation / VirtualDisplay.
 * Uses osmdroid (Canvas tiles) — MapLibre GL often draws a blank surface on bike VD.
 */
class DashMapController(
    private val context: Context,
    host: FrameLayout,
) {
    private val map: MapView = GpxOsmdroid.createMapView(context).also { m ->
        host.addView(
            m,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        m.setTileSource(TileSourceFactory.MAPNIK)
        m.setMultiTouchControls(true)
        m.setUseDataConnection(true)
        m.isTilesScaledToDpi = true
        m.minZoomLevel = 5.0
        m.maxZoomLevel = 20.0
        m.controller.setZoom(16.0)
        m.setBackgroundColor(0xFF101418.toInt())
    }

    /** Remaining route — bright Google-blue. */
    private val remainLine = Polyline(map).apply {
        outlinePaint.color = Color.parseColor("#1A73E8")
        outlinePaint.strokeWidth = dp(7f)
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
    }

    /** Completed path — muted grey so progress is obvious. */
    private val doneLine = Polyline(map).apply {
        outlinePaint.color = Color.parseColor("#9AA0A6")
        outlinePaint.strokeWidth = dp(5.5f)
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        outlinePaint.alpha = 200
    }

    /** Preview route before tapping Go. */
    private val previewLine = Polyline(map).apply {
        outlinePaint.color = Color.parseColor("#34A853")
        outlinePaint.strokeWidth = dp(6.5f)
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        outlinePaint.alpha = 220
    }

    /** Grey alternative routes under the selected green preview. */
    private val altPreviewLines = ArrayList<Polyline>()

    private val toDestLine = Polyline(map).apply {
        outlinePaint.color = Color.parseColor("#FBBC04")
        outlinePaint.strokeWidth = dp(3.5f)
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
        outlinePaint.isAntiAlias = true
    }

    private val me = Marker(map).apply {
        title = "You"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setFlat(true)
        icon = sizedDrawable(R.drawable.ic_nav_arrow, 48, 48)
    }

    private var destMarker: Marker? = null
    private val pinMarkers = ArrayList<Marker>()
    private var buildings3d = true
    private var lastBearing = 0f
    private var onLongPress: ((Double, Double) -> Unit)? = null

    fun setOnLongPress(cb: (Double, Double) -> Unit) {
        onLongPress = cb
    }

    init {
        map.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p != null) onLongPress?.invoke(p.latitude, p.longitude)
                    return p != null
                }
            }),
        )
        map.overlays.add(doneLine)
        map.overlays.add(previewLine)
        map.overlays.add(remainLine)
        map.overlays.add(toDestLine)
        map.overlays.add(me)
        applyNight(NightPrefs.isNightNow(context))
    }

    fun onCreate(@Suppress("UNUSED_PARAMETER") savedInstanceState: Bundle?) {}
    fun onStart() {}
    fun onResume() { map.onResume() }
    fun onPause() { map.onPause() }
    fun onStop() {}
    fun onDestroy() {
        try { map.onDetach() } catch (_: Exception) {}
    }
    fun onLowMemory() {}

    fun setBuildings3d(enabled: Boolean) {
        buildings3d = enabled
    }

    fun applyTheme(night: Boolean) = applyNight(night)

    private fun applyNight(night: Boolean) {
        map.overlayManager.tilesOverlay.setColorFilter(
            if (night) TilesOverlay.INVERT_COLORS else null,
        )
        map.invalidate()
    }

    fun setRouteRemain(points: List<Pair<Double, Double>>) {
        previewLine.setPoints(emptyList())
        remainLine.setPoints(points.map { GeoPoint(it.first, it.second) })
        map.invalidate()
    }

    fun setRouteDone(points: List<Pair<Double, Double>>) {
        doneLine.setPoints(points.map { GeoPoint(it.first, it.second) })
        map.invalidate()
    }

    fun setPreviewRoute(points: List<Pair<Double, Double>>) {
        remainLine.setPoints(emptyList())
        doneLine.setPoints(emptyList())
        clearAltPreviews()
        previewLine.setPoints(points.map { GeoPoint(it.first, it.second) })
        map.invalidate()
    }

    /** Selected green + grey alternatives (Google-style preview). */
    fun setPreviewRoutes(
        selected: List<Pair<Double, Double>>,
        alternatives: List<List<Pair<Double, Double>>>,
    ) {
        remainLine.setPoints(emptyList())
        doneLine.setPoints(emptyList())
        clearAltPreviews()
        val selIdx = map.overlays.indexOf(previewLine).coerceAtLeast(0)
        for (alt in alternatives.take(4)) {
            if (alt.size < 2) continue
            val line = Polyline(map).apply {
                outlinePaint.color = Color.parseColor("#9AA0A6")
                outlinePaint.strokeWidth = dp(5f)
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
                outlinePaint.alpha = 160
                setPoints(alt.map { GeoPoint(it.first, it.second) })
            }
            altPreviewLines.add(line)
            map.overlays.add(selIdx, line) // under the green selected route
        }
        previewLine.setPoints(selected.map { GeoPoint(it.first, it.second) })
        map.invalidate()
    }

    private fun clearAltPreviews() {
        for (line in altPreviewLines) {
            try { map.overlays.remove(line) } catch (_: Exception) {}
        }
        altPreviewLines.clear()
    }

    fun clearPreviewRoute() {
        clearAltPreviews()
        previewLine.setPoints(emptyList())
        map.invalidate()
    }

    fun setToDest(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        toDestLine.setPoints(listOf(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon)))
        map.invalidate()
    }

    fun clearToDest() {
        toDestLine.setPoints(emptyList())
        map.invalidate()
    }

    fun clearRoutes() {
        remainLine.setPoints(emptyList())
        doneLine.setPoints(emptyList())
        clearAltPreviews()
        previewLine.setPoints(emptyList())
        toDestLine.setPoints(emptyList())
        map.invalidate()
    }

    fun setMe(lat: Double, lon: Double, bearingDeg: Float = lastBearing) {
        lastBearing = bearingDeg
        me.position = GeoPoint(lat, lon)
        // Flat marker: rotation is relative to map north → arrow points along travel.
        me.rotation = bearingDeg
        map.invalidate()
    }

    fun setDestination(place: MapPlace?) {
        destMarker?.let { map.overlays.remove(it) }
        destMarker = null
        if (place == null || (place.lat == 0.0 && place.lon == 0.0)) {
            map.invalidate()
            return
        }
        val mk = Marker(map).apply {
            position = GeoPoint(place.lat, place.lon)
            title = place.name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = sizedDrawable(R.drawable.ic_dest_pin, 36, 44)
        }
        destMarker = mk
        map.overlays.add(mk)
        map.invalidate()
    }

    fun setPins(places: List<MapPlace>) {
        for (m in pinMarkers) map.overlays.remove(m)
        pinMarkers.clear()
        for (p in places) {
            if (p.lat == 0.0 && p.lon == 0.0) continue
            // Destination uses setDestination — skip duplicates by name/coords loosely.
            val mk = Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                title = p.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = sizedDrawable(R.drawable.ic_dest_pin, 28, 34)
            }
            pinMarkers.add(mk)
            map.overlays.add(mk)
        }
        // Keep rider + dest on top.
        map.overlays.remove(me)
        map.overlays.add(me)
        destMarker?.let {
            map.overlays.remove(it)
            map.overlays.add(it)
        }
        map.invalidate()
    }

    /**
     * Follow camera. [headingUp]=true rotates the map with travel; false keeps north-up.
     * Arrow always shows travel direction relative to the map.
     */
    fun follow(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        zoom: Double,
        headingUp: Boolean = true,
        moving: Boolean = true,
    ) {
        lastBearing = bearingDeg
        me.position = GeoPoint(lat, lon)
        if (headingUp) {
            map.mapOrientation = -bearingDeg
            me.rotation = bearingDeg // points up on screen while heading-up
        } else {
            map.mapOrientation = 0f
            me.rotation = bearingDeg // points along true travel on north-up map
        }
        val lookAheadM = when {
            !moving -> 0.0
            !headingUp -> 0.0
            zoom >= 17 -> 40.0
            zoom >= 15.5 -> 70.0
            zoom >= 14 -> 110.0
            else -> 160.0
        }
        val center = if (lookAheadM > 1.0) {
            offsetMeters(lat, lon, bearingDeg, lookAheadM)
        } else {
            GeoPoint(lat, lon)
        }
        map.controller.setCenter(center)
        if (kotlin.math.abs(map.zoomLevelDouble - zoom) >= 0.35) {
            map.controller.setZoom(zoom)
        }
        map.invalidate()
    }

    fun setCenter(lat: Double, lon: Double, zoom: Double = 16.0) {
        map.controller.setZoom(zoom)
        map.controller.setCenter(GeoPoint(lat, lon))
        map.invalidate()
    }

    fun zoomToRoute(points: List<Pair<Double, Double>>, padPx: Int = 72) {
        if (points.isEmpty()) return
        if (points.size < 2) {
            map.mapOrientation = 0f
            setCenter(points.first().first, points.first().second, 16.0)
            return
        }
        // Wait until the MapView has a real size — zoomToBoundingBox crashes / no-ops at 0x0
        // (common when swapping GPX into an existing phone preview).
        val apply = Runnable {
            try {
                if (map.width <= 0 || map.height <= 0) {
                    map.postDelayed({ zoomToRoute(points, padPx) }, 50)
                    return@Runnable
                }
                // Overview is ALWAYS north-up (never keep leftover heading-up rotation).
                map.mapOrientation = 0f
                val step = (points.size / 500).coerceAtLeast(1)
                val geo = points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
                    .map { GeoPoint(it.first, it.second) }
                val box = BoundingBox.fromGeoPoints(geo)
                if (box.latNorth.isNaN() || box.latSouth.isNaN()) return@Runnable
                map.zoomToBoundingBox(box, false, padPx)
                map.invalidate()
            } catch (e: Exception) {
                points.firstOrNull()?.let { setCenter(it.first, it.second, 14.0) }
            }
        }
        if (map.width > 0 && map.height > 0) apply.run() else map.post(apply)
    }

    fun zoomToRoutes(routes: List<List<Pair<Double, Double>>>) {
        val flat = routes.flatten()
        if (flat.isEmpty()) return
        map.mapOrientation = 0f
        // Larger pad so the preview card / FABs don't eat the route overview.
        zoomToRoute(flat, padPx = 160)
        map.postDelayed({
            map.mapOrientation = 0f
            zoomToRoute(flat, padPx = 160)
        }, 350)
    }

    fun zoomBy(delta: Double) {
        val z = (map.zoomLevelDouble + delta).coerceIn(5.0, 20.0)
        map.controller.setZoom(z)
    }

    fun resetNorth() {
        map.mapOrientation = 0f
        map.invalidate()
    }

    private fun sizedDrawable(resId: Int, wDp: Int, hDp: Int): Drawable {
        val src = ContextCompat.getDrawable(context, resId)!!.mutate()
        val w = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, wDp.toFloat(), context.resources.displayMetrics,
        ).toInt().coerceAtLeast(1)
        val h = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, hDp.toFloat(), context.resources.displayMetrics,
        ).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        src.setBounds(0, 0, w, h)
        src.draw(canvas)
        return BitmapDrawable(context.resources, bmp)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private fun offsetMeters(lat: Double, lon: Double, bearingDeg: Float, meters: Double): GeoPoint {
        val br = Math.toRadians(bearingDeg.toDouble())
        val dLat = (meters * cos(br)) / 111_320.0
        val dLon = (meters * sin(br)) / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return GeoPoint(lat + dLat, lon + dLon)
    }
}
