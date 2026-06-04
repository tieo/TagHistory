package io.github.tieo.taghistory.ui.map

import android.os.Bundle
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val DEFAULT_ZOOM = 16.0
private const val MEANINGFUL_ZOOM_FLOOR = 6.0

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

// Pin visual tuning — scale/alpha/rotation targets feed animateFloatAsState.
private const val SCALE_SELECTED = 1.15f
private const val SCALE_UNSELECTED = 0.75f
private const val ALPHA_SELECTED = 0.98f
private const val ALPHA_UNSELECTED = 0.55f
private const val ROTATION_UNSELECTED = -6f
private const val PIN_ANIM_MS = 280

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
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
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

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

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
            val targetZoom = maxOf(currentZoom, DEFAULT_ZOOM)
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

private const val CAMERA_MIN_MS = 300
private const val CAMERA_MAX_MS = 1200
private const val CAMERA_LERP_KM = 100.0

private fun cameraDurationFor(distMeters: Double): Int {
    val km = distMeters / 1000.0
    val frac = (km / CAMERA_LERP_KM).coerceIn(0.0, 1.0)
    return (CAMERA_MIN_MS + frac * (CAMERA_MAX_MS - CAMERA_MIN_MS)).toInt()
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2).let { it * it } +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
        sin(dLon / 2).let { it * it }
    val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return r * c
}

/**
 * Unified pill marker — emoji + name in one rounded chip with a small
 * downward tail. Replaces the old Material `LocationOn` icon + separate
 * label-box combo (disjointed, over-weight). Scale + alpha animate on
 * selection via `animateFloatAsState`; chip width adapts to text length
 * via Row's intrinsic sizing. Tail tip anchors exactly at lat/lon.
 */
@Composable
private fun ChipMarker(
    marker: BeaconMarkerUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    screenX: Float,
    screenY: Float,
) {
    val anim = tween<Float>(durationMillis = PIN_ANIM_MS, easing = FastOutSlowInEasing)
    val scale by animateFloatAsState(
        targetValue = if (isSelected) SCALE_SELECTED else SCALE_UNSELECTED,
        animationSpec = anim,
        label = "chipScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isSelected) ALPHA_SELECTED else ALPHA_UNSELECTED,
        animationSpec = anim,
        label = "chipAlpha",
    )

    // Same red for every chip — selected is brighter, unselected deeper
    // so the accent reads as a single brand colour, not two ad-hoc tints.
    val pillColor = if (isSelected) Color(0xFFE53935) else Color(0xFF8B2A2A)
    val contentColor = Color.White

    val tailHeight = 7.dp

    // Position chip via `Modifier.layout` — places post-measure so tail
    // tip lands exactly at (screenX, screenY) with no first-frame jitter
    // or left-shift (previous `onGloballyPositioned + offset` approach
    // flashed the chip at (0,y) until width was read back from layout).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(
                        x = (screenX - placeable.width / 2f).toInt(),
                        y = (screenY - placeable.height.toFloat()).toInt(),
                    )
                }
            }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
    ) {
        Row(
            modifier = Modifier
                .shadow(6.dp, RoundedCornerShape(50))
                .background(pillColor, RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val emoji = marker.emoji?.takeIf { it.isNotBlank() }
            if (emoji != null) {
                Text(text = emoji, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = marker.displayName,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Canvas(modifier = Modifier.size(12.dp, tailHeight)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path, pillColor)
        }
    }
}

/**
 * Compass FAB — fades in when bearing != 0, fades out at 0. Needle
 * animates to match bearing so user sees tilt. Tap → reset camera.
 * All transitions tweened for smooth appearance / disappearance.
 */
@Composable
private fun NorthLockButton(
    bearing: Float,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = kotlin.math.abs(bearing) > 0.5f
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "compassAlpha",
    )
    val needleAngle by animateFloatAsState(
        targetValue = -bearing,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "compassAngle",
    )
    if (alpha <= 0.01f) return
    FilledIconButton(
        onClick = onReset,
        modifier = modifier.alpha(alpha),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.Navigation,
            contentDescription = "Reset to north",
            modifier = Modifier.graphicsLayer { rotationZ = needleAngle },
        )
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
                PropertyFactory.fillColor("#8B2A2A"),
                PropertyFactory.fillOpacity(0.10f),
            ),
        )
    }
    if (style.getLayer(ACCURACY_STROKE_LAYER) == null) {
        style.addLayer(
            LineLayer(ACCURACY_STROKE_LAYER, ACCURACY_SOURCE).withProperties(
                PropertyFactory.lineColor("#8B2A2A"),
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
        val ring = circleRing(m.latitude, m.longitude, r, segments = 48)
        Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
    }
    src.setGeoJson(FeatureCollection.fromFeatures(features))
    // selectedBeaconId currently unused for tint; chip is the primary
    // selection indicator. Keep parameter for future selected-feature
    // expression.
    @Suppress("UNUSED_EXPRESSION") selectedBeaconId
}

/** WGS-84 circle approximation as a closed polygon ring. */
private fun circleRing(
    centerLat: Double,
    centerLon: Double,
    radiusM: Double,
    segments: Int,
): List<Point> {
    val earthR = 6_371_000.0
    val lat0 = centerLat * PI / 180.0
    val lon0 = centerLon * PI / 180.0
    val angularDist = radiusM / earthR
    val out = ArrayList<Point>(segments + 1)
    for (i in 0..segments) {
        val bearing = 2.0 * PI * i / segments
        val newLat = asin(
            sin(lat0) * cos(angularDist) +
                cos(lat0) * sin(angularDist) * cos(bearing),
        )
        val newLon = lon0 + atan2(
            sin(bearing) * sin(angularDist) * cos(lat0),
            cos(angularDist) - sin(lat0) * sin(newLat),
        )
        out += Point.fromLngLat(newLon * 180.0 / PI, newLat * 180.0 / PI)
    }
    return out
}

