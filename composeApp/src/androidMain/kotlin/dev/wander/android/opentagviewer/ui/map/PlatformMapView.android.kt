package io.github.tieo.taghistory.ui.map

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon


/**
 * Converts [accuracyM] metres to screen pixels at the map's current zoom
 * and [lat], by projecting a point ~[accuracyM] metres north and computing
 * the screen-pixel distance to [lat]. Returns 0 when accuracy is unknown.
 */
private fun accuracyToScreenPx(
    map: MapLibreMap,
    lat: Double,
    lon: Double,
    accuracyM: Long,
): Float {
    if (accuracyM <= 0L) return 0f
    val center = map.projection.toScreenLocation(LatLng(lat, lon))
    val deltaLat = accuracyM / 111_320.0
    val north = map.projection.toScreenLocation(LatLng(lat + deltaLat, lon))
    return abs(north.y - center.y).toFloat()
}

/**
 * Map surface. MapLibre renders basemap tiles only — beacon markers live
 * in a Compose overlay on top, projecting lat/lon → screen pixel via
 * `map.projection.toScreenLocation` on every camera move. Gives us real
 * Compose animations (`animateFloatAsState`) on scale / alpha / rotation
 * that data-driven MapLibre style properties can't deliver for layout
 * props like `iconSize` / `iconRotate`.
 */
@Composable
actual fun PlatformMapView(
    markers: List<BeaconMarkerUi>,
    selectedBeaconId: String?,
    initialCamera: UserMapCameraPosition?,
    basemap: MapBasemap,
    onMarkerClick: (String) -> Unit,
    onCameraIdle: (UserMapCameraPosition) -> Unit,
    bottomInsetPx: Int,
    modifier: Modifier,
) {
    val context = LocalContext.current

    val mapView = remember {
        MapLibre.getInstance(context)
        // TextureView, not the default SurfaceView. A SurfaceView lives in its
        // own window layer; with the translucent glass tag list composited on
        // top, real-GPU devices fail to blend the layers and paint the whole
        // window blank white (SwiftShader on the emulator masks it). TextureView
        // renders into the view hierarchy so Compose can alpha-blend over it.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
            .apply { onCreate(Bundle()) }
    }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    // Every camera move (pan, zoom, rotate, fling) increments this — Compose
    // reads it as a trigger to re-project marker positions.
    var cameraTick by remember { mutableIntStateOf(0) }
    // Bearing in degrees (0 = north up). Drives the north-lock FAB's
    // visibility + compass-needle rotation. Read via OnCameraMoveListener.
    var bearing by remember { mutableFloatStateOf(0f) }
    val didInitialFocus = remember { booleanArrayOf(false) }
    val lastAppliedBasemap = remember { arrayOf(basemap) }

    BindMapViewLifecycle(mapView)

    // Camera-padding follow: when the glass tag list height changes (e.g.
    // appears after the first import), update map padding so subsequent
    // newLatLngZoom calls center within the visible region above it.
    LaunchedEffect(bottomInsetPx) {
        mapView.getMapAsync { map ->
            map.setPadding(0, 0, 0, bottomInsetPx)
        }
    }

    // Accuracy circles live in the map style now, so they tilt + rotate
    // with the camera (the Canvas overlay version stayed pixel-flat and
    // sheared visibly during 3D rotation). Re-emit the source data
    // whenever markers or selection change.
    LaunchedEffect(markers, selectedBeaconId) {
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            applyAccuracyData(style, markers, selectedBeaconId)
        }
    }

    // Theme / basemap cycle — reload style; re-install accuracy layers
    // since setStyle wipes existing sources/layers.
    LaunchedEffect(basemap) {
        if (lastAppliedBasemap[0] == basemap) return@LaunchedEffect
        lastAppliedBasemap[0] = basemap
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromBasemap(basemap, context)) { style ->
                installAccuracyLayers(style)
                applyAccuracyData(style, markers, selectedBeaconId)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { _ ->
                mapView.getMapAsync { map ->
                    // Rotation gesture ON so users can twist-rotate the map;
                    // our north-lock FAB appears whenever bearing != 0 and
                    // resets to 0 on tap.
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isAttributionEnabled = false
                    map.uiSettings.isLogoEnabled = false
                    initialCamera?.takeIf { it.zoom.toDouble() >= MEANINGFUL_ZOOM_FLOOR }?.let {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(it.lat, it.lon))
                            .zoom(it.zoom.toDouble())
                            .build()
                    }
                    map.setStyle(Style.Builder().fromBasemap(basemap, context)) { style ->
                        installAccuracyLayers(style)
                    }
                    // Camera padding: bottomInsetPx is the height of the
                    // glass tag list that overlays the map. With it set,
                    // newLatLngZoom centers the target inside the visible
                    // top portion instead of behind the list.
                    map.setPadding(0, 0, 0, bottomInsetPx)
                    map.addOnCameraMoveListener {
                        cameraTick++
                        bearing = map.cameraPosition.bearing.toFloat()
                    }
                    map.addOnCameraIdleListener {
                        cameraTick++
                        val pos = map.cameraPosition
                        val target = pos.target ?: return@addOnCameraIdleListener
                        onCameraIdle(
                            UserMapCameraPosition(
                                zoom = pos.zoom.toFloat(),
                                lat = target.latitude,
                                lon = target.longitude,
                            )
                        )
                    }
                    mapRef = map
                    // Prime the first projection once map + camera are live.
                    cameraTick++
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Compose overlay. Accuracy circles first (behind chips), then
        // unselected chips, then selected chip on top.
        val map = mapRef
        if (map != null) {
            @Suppress("UNUSED_VARIABLE")
            val tick = cameraTick
            val density = LocalDensity.current.density
            val unselected = markers.filter { it.beaconId != selectedBeaconId }
            val selected = markers.firstOrNull { it.beaconId == selectedBeaconId }

            for (m in unselected) {
                val screen = map.projection.toScreenLocation(LatLng(m.latitude, m.longitude))
                ChipMarker(
                    marker = m,
                    isSelected = false,
                    onClick = { onMarkerClick(m.beaconId) },
                    screenX = screen.x,
                    screenY = screen.y,
                )
            }
            if (selected != null) {
                val screen = map.projection.toScreenLocation(LatLng(selected.latitude, selected.longitude))
                ChipMarker(
                    marker = selected,
                    isSelected = true,
                    onClick = { onMarkerClick(selected.beaconId) },
                    screenX = screen.x,
                    screenY = screen.y,
                )
            }
        }

        // North-lock FAB — fades in when bearing != 0, fades out at 0. Needle
        // rotates to match current bearing so the user can see how much the
        // map is off-north. Tap animates camera back to bearing=0.
        NorthLockButton(
            bearing = bearing,
            onReset = {
                mapView.getMapAsync { map ->
                    map.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 72.dp, end = 12.dp),
        )
    }

    // Follow selection — only when the selected tag's identity/coords change,
    // not on every recomposition.
    val selectedMarker = markers.firstOrNull { it.beaconId == selectedBeaconId }
    val cameraKey = selectedMarker?.let { "${it.beaconId}|${it.latitude}|${it.longitude}" }
    LaunchedEffect(cameraKey) {
        val target = selectedMarker ?: return@LaunchedEffect
        // Claim "we have done at least one focus" synchronously so a
        // recomposition arriving before getMapAsync's callback sees the
        // up-to-date flag. Doing this inside the async block was caught
        // by :verifyNoAsyncMapStateWrite — same hazard class as the
        // map-history zoom-jump fixed in 7fd54a0.
        didInitialFocus[0] = true
        mapView.getMapAsync { map ->
            val currentZoom = map.cameraPosition.zoom
            val current = map.cameraPosition.target
            // Zoom in to at least street level; preserve higher zoom if already there.
            val targetZoom = maxOf(currentZoom, DEFAULT_FOCUS_ZOOM)
            val update = CameraUpdateFactory.newLatLngZoom(
                LatLng(target.latitude, target.longitude),
                targetZoom,
            )
            // Duration scales with how far the camera has to travel:
            // a tag-switch across the country gets a long, smooth pan;
            // a re-tap on the already-centered card snaps. Lerp from
            // CAMERA_MIN_MS at 0 km to CAMERA_MAX_MS at CAMERA_LERP_KM
            // and clamp.
            val distM = if (current == null) 0.0 else haversineMeters(
                current.latitude, current.longitude,
                target.latitude, target.longitude,
            )
            val dur = cameraDurationFor(distM)
            map.animateCamera(update, dur)
        }
    }
}

private const val ACCURACY_SOURCE = "map-accuracy-src"
private const val ACCURACY_FILL_LAYER = "map-accuracy-fill"
private const val ACCURACY_STROKE_LAYER = "map-accuracy-stroke"

/** Install (idempotent) the source + fill/stroke layers for accuracy circles. */
private fun installAccuracyLayers(style: Style) {
    if (style.getSource(ACCURACY_SOURCE) == null) {
        style.addSourceAt(0, GeoJsonSource(ACCURACY_SOURCE, FeatureCollection.fromFeatures(emptyList())))
    }
    if (style.getLayer(ACCURACY_FILL_LAYER) == null) {
        style.addLayer(
            FillLayer(ACCURACY_FILL_LAYER, ACCURACY_SOURCE).withProperties(
                PropertyFactory.fillColor(ACCURACY_COLOR),
                PropertyFactory.fillOpacity(0.10f),
            ),
        )
    }
    if (style.getLayer(ACCURACY_STROKE_LAYER) == null) {
        style.addLayer(
            LineLayer(ACCURACY_STROKE_LAYER, ACCURACY_SOURCE).withProperties(
                PropertyFactory.lineColor(ACCURACY_COLOR),
                PropertyFactory.lineOpacity(0.30f),
                PropertyFactory.lineWidth(1.5f),
            ),
        )
    }
}

/** Workaround helper: MapLibre's Style.addSource has no index overload. */
private fun Style.addSourceAt(@Suppress("UNUSED_PARAMETER") index: Int, src: GeoJsonSource) {
    addSource(src)
}

private fun applyAccuracyData(
    style: Style,
    markers: List<BeaconMarkerUi>,
    selectedBeaconId: String?,
) {
    val src = style.getSourceAs<GeoJsonSource>(ACCURACY_SOURCE) ?: return
    val features = markers.mapNotNull { m ->
        val r = m.horizontalAccuracy.toDouble()
        if (r < 5.0) return@mapNotNull null
        val ring = circleRing(m.latitude, m.longitude, r, segments = 48).map { (lon, lat) -> Point.fromLngLat(lon, lat) }
        Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }
    src.setGeoJson(FeatureCollection.fromFeatures(features))
    // selectedBeaconId currently unused for tint; chip is the primary
    // selection indicator. Keep parameter for future selected-feature
    // expression.
    @Suppress("UNUSED_EXPRESSION") selectedBeaconId
}
