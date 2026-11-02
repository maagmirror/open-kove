// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import androidx.core.content.ContextCompat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MapLibre vector map for phone Dash preview — OpenFreeMap style with pitch/3D when enabled.
 */
class MapLibreDashController(
    private val context: Context,
    private val mapView: MapView,
) {
    private var map: MapLibreMap? = null
    private var styleReady = false
    private var pendingStyleUri: String? = null
    private var buildings3d = true

    private var remainPts: List<LatLng> = emptyList()
    private var donePts: List<LatLng> = emptyList()
    private var previewPts: List<LatLng> = emptyList()
    private var altRoutes: List<List<LatLng>> = emptyList()
    private var toDestPts: List<LatLng> = emptyList()
    private var me: LatLng? = null
    private var meBearing = 0f
    private var dest: LatLng? = null
    private var pins: List<Pair<String, LatLng>> = emptyList()
    private var onLongPress: ((Double, Double) -> Unit)? = null

    fun setOnLongPress(cb: (Double, Double) -> Unit) {
        onLongPress = cb
    }

    fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        // Offline diagnostics: if the style/tiles can't load and no downloaded region covers the
        // view, this fires — the bike keeps its osmdroid raster engine as the offline map.
        mapView.addOnDidFailLoadingMapListener { err ->
            LogBus.log("[MAP] MapLibre load failed (offline? no region for area?): $err")
        }
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.setAllGesturesEnabled(true)
            // Hide MapLibre's built-in top compass — it sits under our full-width search pill.
            // We have our own compass FAB (gpx_btn_north) in the quick bar instead.
            m.uiSettings.isCompassEnabled = false
            m.addOnMapLongClickListener { ll ->
                onLongPress?.invoke(ll.latitude, ll.longitude)
                true
            }
            val uri = pendingStyleUri ?: styleUri(night = NightPrefs.isNightNow(context))
            loadStyle(uri)
        }
    }

    fun onStart() = mapView.onStart()
    fun onResume() {
        mapView.onStart()
        mapView.onResume()
    }
    fun onPause() {
        mapView.onPause()
        mapView.onStop()
    }
    fun onStop() = mapView.onStop()
    fun onDestroy() {
        try { mapView.onDestroy() } catch (_: Exception) {}
        map = null
        styleReady = false
    }
    fun onLowMemory() = mapView.onLowMemory()

    fun setBuildings3d(enabled: Boolean) {
        buildings3d = enabled
        applyCameraExtras()
    }

    fun applyTheme(night: Boolean) {
        loadStyle(styleUri(night))
    }

    fun setRouteRemain(points: List<Pair<Double, Double>>) {
        previewPts = emptyList()
        remainPts = points.map { LatLng(it.first, it.second) }
        pushLines()
    }

    fun setRouteDone(points: List<Pair<Double, Double>>) {
        donePts = points.map { LatLng(it.first, it.second) }
        pushLines()
    }

    fun setPreviewRoute(points: List<Pair<Double, Double>>) {
        remainPts = emptyList()
        donePts = emptyList()
        previewPts = points.map { LatLng(it.first, it.second) }
        altRoutes = emptyList()
        pushLines()
    }

    /** Selected route (highlighted) plus greyed alternatives underneath (Google-style). */
    fun setPreviewRoutes(
        selected: List<Pair<Double, Double>>,
        alternatives: List<List<Pair<Double, Double>>>,
    ) {
        remainPts = emptyList()
        donePts = emptyList()
        toDestPts = emptyList()
        previewPts = selected.map { LatLng(it.first, it.second) }
        altRoutes = alternatives.map { alt -> alt.map { LatLng(it.first, it.second) } }
        pushLines()
    }

    fun clearPreviewRoute() {
        previewPts = emptyList()
        altRoutes = emptyList()
        pushLines()
    }

    fun setToDest(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        toDestPts = listOf(LatLng(fromLat, fromLon), LatLng(toLat, toLon))
        pushLines()
    }

    fun clearToDest() {
        toDestPts = emptyList()
        pushLines()
    }

    fun clearRoutes() {
        remainPts = emptyList()
        donePts = emptyList()
        previewPts = emptyList()
        altRoutes = emptyList()
        toDestPts = emptyList()
        pushLines()
    }

    fun setMe(lat: Double, lon: Double, bearingDeg: Float = 0f) {
        me = LatLng(lat, lon)
        meBearing = bearingDeg
        pushMe()
    }

    fun setDestination(place: MapPlace?) {
        dest = if (place == null || (place.lat == 0.0 && place.lon == 0.0)) {
            null
        } else {
            LatLng(place.lat, place.lon)
        }
        pushPins()
    }

    fun setPins(places: List<MapPlace>) {
        pins = places
            .filter { !(it.lat == 0.0 && it.lon == 0.0) }
            .map { it.name to LatLng(it.lat, it.lon) }
        pushPins()
    }

    fun follow(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        zoom: Double,
        headingUp: Boolean = true,
        moving: Boolean = true,
    ) {
        val m = map ?: return
        me = LatLng(lat, lon)
        meBearing = bearingDeg
        pushMe()
        val tilt = if (buildings3d) 52.0 else 0.0
        val bearing = if (headingUp) bearingDeg.toDouble() else 0.0
        val target = if (moving && headingUp) {
            offsetMeters(lat, lon, bearingDeg, 70.0)
        } else {
            LatLng(lat, lon)
        }
        val pos = CameraPosition.Builder()
            .target(target)
            .zoom(zoom.coerceIn(11.0, 18.5))
            .bearing(bearing)
            .tilt(tilt)
            .build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 280)
    }

    fun setCenter(lat: Double, lon: Double, zoom: Double = 14.0) {
        val m = map ?: return
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .tilt(if (buildings3d) 52.0 else 0.0)
                    .build(),
            ),
        )
    }

    fun zoomToRoute(points: List<Pair<Double, Double>>, padPx: Int = 72) =
        zoomToRoutes(listOf(points))

    /**
     * Google/Waze-style route overview: top-down (tilt 0), whole polyline + endpoints framed
     * with chrome padding. Retries until the MapView has a real size — otherwise bounds
     * fitting fails and we used to fall back to a single point (looks like a "partial" line).
     */
    fun zoomToRoutes(routes: List<List<Pair<Double, Double>>>) {
        val all = routes.filter { it.isNotEmpty() }
        if (all.isEmpty()) return
        val flat = all.flatten()
        if (flat.size < 2) {
            setCenter(flat.first().first, flat.first().second, 16.0)
            return
        }
        val b = LatLngBounds.Builder()
        // Always include every route's start/end so short samples can't drop the destination.
        for (r in all) {
            b.include(LatLng(r.first().first, r.first().second))
            b.include(LatLng(r.last().first, r.last().second))
        }
        val step = (flat.size / 400).coerceAtLeast(1)
        flat.forEachIndexed { i, p ->
            if (i % step == 0) b.include(LatLng(p.first, p.second))
        }
        val bounds = try {
            b.build()
        } catch (_: Exception) {
            setCenter(flat.first().first, flat.first().second, 14.0)
            return
        }
        fun apply(attempt: Int) {
            val m = map ?: return
            if (mapView.width <= 0 || mapView.height <= 0) {
                if (attempt < 50) mapView.postDelayed({ apply(attempt + 1) }, 40)
                return
            }
            val d = mapView.resources.displayMetrics.density
            // Generous padding so the whole route sits in the clear map between chrome.
            val left = (28 * d).toInt()
            val top = (88 * d).toInt()
            val right = (96 * d).toInt()
            val bottom = (168 * d).toInt() // preview card + alts row
            try {
                val padding = intArrayOf(left, top, right, bottom)
                val fitted = m.getCameraForLatLngBounds(bounds, padding)
                val overview = if (fitted != null) {
                    CameraPosition.Builder(fitted).tilt(0.0).bearing(0.0).build()
                } else {
                    // Fallback: bounds update then force a flat overview.
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, left, top, right, bottom))
                    CameraPosition.Builder(m.cameraPosition).tilt(0.0).bearing(0.0).build()
                }
                m.easeCamera(CameraUpdateFactory.newCameraPosition(overview), 650)
            } catch (_: Exception) {
                if (attempt < 50) {
                    mapView.postDelayed({ apply(attempt + 1) }, 60)
                } else {
                    // Last resort: midpoint of the route, still flat.
                    val mid = flat[flat.size / 2]
                    m.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(mid.first, mid.second))
                                .zoom(12.0)
                                .tilt(0.0)
                                .bearing(0.0)
                                .build(),
                        ),
                    )
                }
            }
        }
        mapView.post { apply(0) }
        // Second pass after the preview card has laid out (bottom inset is then correct).
        mapView.postDelayed({ apply(0) }, 350)
    }

    fun zoomBy(delta: Double) {
        val m = map ?: return
        m.easeCamera(CameraUpdateFactory.zoomBy(delta), 180)
    }

    fun resetNorth() {
        val m = map ?: return
        val cur = m.cameraPosition
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(cur).bearing(0.0).build(),
            ),
            280,
        )
    }

    private fun applyCameraExtras() {
        val m = map ?: return
        val cur = m.cameraPosition
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(cur).tilt(if (buildings3d) 52.0 else 0.0).build(),
            ),
        )
    }

    private fun styleUri(night: Boolean): String =
        RegionPackStore.localStyleUri(context, night)
            ?: if (night) STYLE_NIGHT else STYLE_DAY

    private fun loadStyle(uri: String) {
        pendingStyleUri = uri
        val m = map ?: return
        styleReady = false
        m.setStyle(Style.Builder().fromUri(uri)) { style ->
            ensureBuildingExtrusion(style)
            ensureOverlayLayers(style)
            styleReady = true
            pushLines()
            pushMe()
            pushPins()
            applyCameraExtras()
            LogBus.log("[MAP] MapLibre style ready: $uri")
        }
    }

    /**
     * Guarantee 3D buildings regardless of the loaded style: add a fill-extrusion layer
     * off the OpenMapTiles `building` source-layer if the style doesn't already have one.
     */
    private fun ensureBuildingExtrusion(style: Style) {
        if (style.getLayer(LAYER_BUILDINGS) != null) return
        // Find a vector source that carries the OpenMapTiles building layer.
        val srcId = style.sources.firstOrNull { it.id == "openmaptiles" }?.id
            ?: style.sources.firstOrNull()?.id ?: return
        try {
            val ext = FillExtrusionLayer(LAYER_BUILDINGS, srcId).apply {
                sourceLayer = "building"
                minZoom = 14f
                setProperties(
                    fillExtrusionColor(Color.parseColor("#C8CCD4")),
                    fillExtrusionOpacity(0.7f),
                    fillExtrusionHeight(
                        Expression.coalesce(
                            Expression.get("render_height"),
                            Expression.get("height"),
                            Expression.literal(6),
                        ),
                    ),
                    fillExtrusionBase(
                        Expression.coalesce(
                            Expression.get("render_min_height"),
                            Expression.get("min_height"),
                            Expression.literal(0),
                        ),
                    ),
                )
            }
            // Insert below our own overlay lines/markers so routes stay on top.
            style.addLayer(ext)
        } catch (e: Exception) {
            LogBus.log("[MAP] 3D buildings unavailable: $e")
        }
    }

    private fun ensureOverlayLayers(style: Style) {
        if (style.getSource(SRC_REMAIN) != null) return
        ensureImages(style)
        style.addSource(GeoJsonSource(SRC_REMAIN))
        style.addSource(GeoJsonSource(SRC_DONE))
        style.addSource(GeoJsonSource(SRC_ALTS))
        style.addSource(GeoJsonSource(SRC_PREVIEW))
        style.addSource(GeoJsonSource(SRC_TODEST))
        style.addSource(GeoJsonSource(SRC_ME))
        style.addSource(GeoJsonSource(SRC_PINS))
        style.addSource(GeoJsonSource(SRC_DEST))
        // Alternative routes: greyed, drawn under the selected route.
        style.addLayer(
            LineLayer(LAYER_ALTS, SRC_ALTS).withProperties(
                lineColor(Color.parseColor("#9AA0A6")),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(LAYER_DONE, SRC_DONE).withProperties(
                lineColor(Color.parseColor("#9AA0A6")),
                lineWidth(5.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(LAYER_PREVIEW, SRC_PREVIEW).withProperties(
                lineColor(Color.parseColor("#34A853")),
                lineWidth(6.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(LAYER_REMAIN, SRC_REMAIN).withProperties(
                lineColor(Color.parseColor("#1A73E8")),
                lineWidth(6.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(LAYER_TODEST, SRC_TODEST).withProperties(
                lineColor(Color.parseColor("#FBBC04")),
                lineWidth(3.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(LAYER_PINS, SRC_PINS).withProperties(
                circleColor(Color.parseColor("#FFCA28")),
                circleRadius(6f),
                circleStrokeColor(Color.BLACK),
                circleStrokeWidth(1.2f),
            ),
        )
        // Destination: teardrop pin anchored at its point.
        style.addLayer(
            SymbolLayer(LAYER_DEST, SRC_DEST).withProperties(
                iconImage(IMG_DEST),
                iconSize(0.85f),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        // Me: heading arrow rotated by GPS bearing, flat on the map (Waze/Google style).
        style.addLayer(
            SymbolLayer(LAYER_ME, SRC_ME).withProperties(
                iconImage(IMG_ME),
                iconSize(0.9f),
                iconRotate(Expression.get("bearing")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
    }

    private fun ensureImages(style: Style) {
        drawableToBitmap(R.drawable.ic_nav_arrow)?.let { style.addImage(IMG_ME, it) }
        drawableToBitmap(R.drawable.ic_dest_pin)?.let { style.addImage(IMG_DEST, it) }
    }

    private fun drawableToBitmap(resId: Int): Bitmap? {
        val d = ContextCompat.getDrawable(context, resId) ?: return null
        val w = (d.intrinsicWidth.takeIf { it > 0 } ?: 48)
        val h = (d.intrinsicHeight.takeIf { it > 0 } ?: 48)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    private fun pushLines() {
        val style = map?.style ?: return
        if (!styleReady) return
        (style.getSource(SRC_REMAIN) as? GeoJsonSource)?.setGeoJson(lineCollection(remainPts))
        (style.getSource(SRC_DONE) as? GeoJsonSource)?.setGeoJson(lineCollection(donePts))
        (style.getSource(SRC_ALTS) as? GeoJsonSource)?.setGeoJson(linesCollection(altRoutes))
        (style.getSource(SRC_PREVIEW) as? GeoJsonSource)?.setGeoJson(lineCollection(previewPts))
        (style.getSource(SRC_TODEST) as? GeoJsonSource)?.setGeoJson(lineCollection(toDestPts))
    }

    private fun pushMe() {
        val style = map?.style ?: return
        if (!styleReady) return
        val p = me
        val fc = if (p == null) {
            FeatureCollection.fromFeatures(emptyArray())
        } else {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).also {
                    it.addNumberProperty("bearing", meBearing.toDouble())
                },
            )
        }
        (style.getSource(SRC_ME) as? GeoJsonSource)?.setGeoJson(fc)
    }

    private fun pushPins() {
        val style = map?.style ?: return
        if (!styleReady) return
        val features = pins.map { (name, ll) ->
            Feature.fromGeometry(Point.fromLngLat(ll.longitude, ll.latitude)).also {
                it.addStringProperty("name", name)
            }
        }
        (style.getSource(SRC_PINS) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
        val d = dest
        val destFc = if (d == null) {
            FeatureCollection.fromFeatures(emptyArray())
        } else {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(d.longitude, d.latitude)),
            )
        }
        (style.getSource(SRC_DEST) as? GeoJsonSource)?.setGeoJson(destFc)
    }

    private fun lineCollection(pts: List<LatLng>): FeatureCollection {
        if (pts.size < 2) return FeatureCollection.fromFeatures(emptyArray())
        val coords = pts.map { Point.fromLngLat(it.longitude, it.latitude) }
        return FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(coords)))
    }

    private fun linesCollection(lines: List<List<LatLng>>): FeatureCollection {
        val features = lines.filter { it.size >= 2 }.map { pts ->
            Feature.fromGeometry(
                LineString.fromLngLats(pts.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        return FeatureCollection.fromFeatures(features)
    }

    private fun offsetMeters(lat: Double, lon: Double, bearingDeg: Float, meters: Double): LatLng {
        val br = Math.toRadians(bearingDeg.toDouble())
        val dLat = (meters * kotlin.math.cos(br)) / 111_320.0
        val dLon = (meters * kotlin.math.sin(br)) /
            (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return LatLng(lat + dLat, lon + dLon)
    }

    companion object {
        const val STYLE_DAY = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

        private const val SRC_REMAIN = "ocm-remain"
        private const val SRC_DONE = "ocm-done"
        private const val SRC_ALTS = "ocm-alts"
        private const val SRC_PREVIEW = "ocm-preview"
        private const val SRC_TODEST = "ocm-todest"
        private const val SRC_ME = "ocm-me"
        private const val SRC_PINS = "ocm-pins"
        private const val SRC_DEST = "ocm-dest"
        private const val LAYER_REMAIN = "ocm-remain-l"
        private const val LAYER_DONE = "ocm-done-l"
        private const val LAYER_ALTS = "ocm-alts-l"
        private const val LAYER_PREVIEW = "ocm-preview-l"
        private const val LAYER_TODEST = "ocm-todest-l"
        private const val LAYER_ME = "ocm-me-l"
        private const val LAYER_PINS = "ocm-pins-l"
        private const val LAYER_DEST = "ocm-dest-l"
        private const val LAYER_BUILDINGS = "ocm-buildings-3d"
        private const val IMG_ME = "ocm-me-arrow"
        private const val IMG_DEST = "ocm-dest-pin"
    }
}
