// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import org.maplibre.android.maps.MapView as LibreMapView

/**
 * Phone preview → MapLibre (vector + 3D tilt).
 * Bike Presentation / VirtualDisplay → osmdroid Canvas raster.
 *
 * MapLibre GL on the VirtualDisplay looks fine while still, but when the camera/map moves the
 * incomplete GL frames get H.264-encoded to the bike and show heavy block artifacts — the
 * in-app phone control never sees those because it skips the encode path.
 */
class DashMapEngine(
    private val context: Context,
    host: FrameLayout,
) {
    private val useLibre: Boolean = context is Activity
    private val libreView: LibreMapView?
    private val libre: MapLibreDashController?
    private val osm: DashMapController?

    init {
        if (useLibre) {
            AppHttp.ensureCellularUplink()
            try {
                org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(AppHttp.mapLibreOkHttpClient())
            } catch (_: Exception) {
            }
            val mv = LibreMapView(context)
            host.addView(
                mv, 0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            libreView = mv
            libre = MapLibreDashController(context, mv)
            osm = null
            LogBus.log("[MAP] engine=MapLibre (phone preview)")
        } else {
            libreView = null
            libre = null
            osm = DashMapController(context, host)
            LogBus.log("[MAP] engine=osmdroid (Presentation / bike — clean H.264)")
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        libre?.onCreate(savedInstanceState)
        osm?.onCreate(savedInstanceState)
    }

    fun onResume() {
        libre?.onResume()
        osm?.onResume()
    }

    fun onPause() {
        libre?.onPause()
        osm?.onPause()
    }

    fun onStop() {
        libre?.onStop()
        osm?.onStop()
    }

    fun onDestroy() {
        try { libre?.onDestroy() } catch (_: Exception) {}
        try { osm?.onDestroy() } catch (_: Exception) {}
    }

    fun setBuildings3d(enabled: Boolean) {
        libre?.setBuildings3d(enabled)
        osm?.setBuildings3d(enabled)
    }

    fun applyTheme(night: Boolean) {
        libre?.applyTheme(night)
        osm?.applyTheme(night)
    }

    fun setRouteRemain(points: List<Pair<Double, Double>>) {
        libre?.setRouteRemain(points)
        osm?.setRouteRemain(points)
    }

    fun setRouteDone(points: List<Pair<Double, Double>>) {
        libre?.setRouteDone(points)
        osm?.setRouteDone(points)
    }

    fun setPreviewRoute(points: List<Pair<Double, Double>>) {
        libre?.setPreviewRoute(points)
        osm?.setPreviewRoute(points)
    }

    fun setPreviewRoutes(
        selected: List<Pair<Double, Double>>,
        alternatives: List<List<Pair<Double, Double>>>,
    ) {
        libre?.setPreviewRoutes(selected, alternatives)
        osm?.setPreviewRoutes(selected, alternatives)
    }

    fun clearPreviewRoute() {
        libre?.clearPreviewRoute()
        osm?.clearPreviewRoute()
    }

    fun setToDest(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) {
        libre?.setToDest(fromLat, fromLon, toLat, toLon)
        osm?.setToDest(fromLat, fromLon, toLat, toLon)
    }

    fun clearToDest() {
        libre?.clearToDest()
        osm?.clearToDest()
    }

    fun clearRoutes() {
        libre?.clearRoutes()
        osm?.clearRoutes()
    }

    fun setMe(lat: Double, lon: Double, bearingDeg: Float = 0f) {
        libre?.setMe(lat, lon, bearingDeg)
        osm?.setMe(lat, lon, bearingDeg)
    }

    fun setDestination(place: MapPlace?) {
        libre?.setDestination(place)
        osm?.setDestination(place)
    }

    fun setPins(places: List<MapPlace>) {
        libre?.setPins(places)
        osm?.setPins(places)
    }

    fun follow(
        lat: Double,
        lon: Double,
        bearingDeg: Float,
        zoom: Double,
        headingUp: Boolean = true,
        moving: Boolean = true,
    ) {
        libre?.follow(lat, lon, bearingDeg, zoom, headingUp, moving)
        osm?.follow(lat, lon, bearingDeg, zoom, headingUp, moving)
    }

    fun setCenter(lat: Double, lon: Double, zoom: Double = 16.0) {
        libre?.setCenter(lat, lon, zoom)
        osm?.setCenter(lat, lon, zoom)
    }

    fun zoomToRoute(points: List<Pair<Double, Double>>, padPx: Int = 72) {
        libre?.zoomToRoute(points, padPx)
        osm?.zoomToRoute(points, padPx)
    }

    fun zoomToRoutes(routes: List<List<Pair<Double, Double>>>) {
        libre?.zoomToRoutes(routes)
        osm?.zoomToRoutes(routes)
    }

    fun zoomBy(delta: Double) {
        libre?.zoomBy(delta)
        osm?.zoomBy(delta)
    }

    fun resetNorth() {
        libre?.resetNorth()
        osm?.resetNorth()
    }

    fun setOnLongPress(cb: (Double, Double) -> Unit) {
        libre?.setOnLongPress(cb)
        osm?.setOnLongPress(cb)
    }
}
