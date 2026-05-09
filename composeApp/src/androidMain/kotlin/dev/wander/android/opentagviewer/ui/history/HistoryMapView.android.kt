package io.github.tieo.taghistory.ui.history

import android.graphics.Color
import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.defaultBasemap
import io.github.tieo.taghistory.ui.map.fromBasemap
import io.github.tieo.taghistory.util.PerfTrace
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val POLYLINE_WIDTH_DP = 6f
private const val BOUNDS_PADDING_PX = 140

private const val PATH_SOURCE = "history-path"
private const val PATH_LAYER = "history-path-line"
private const val ENDPOINTS_SOURCE = "history-endpoints"
private const val ENDPOINTS_LAYER = "history-endpoints-circle"
private const val SELECTED_SOURCE = "history-selected"
private const val SELECTED_LAYER = "history-selected-circle"
private const val PROP_ROLE = "role"
private const val ROLE_START = "start"
private const val ROLE_END = "end"

@Composable
actual fun HistoryMapView(
    points: List<HistoryPoint>,
    selectedPointIndex: Int?,
    basemap: MapBasemap?,
    routeVisible: Boolean,
    bottomInsetPx: Int,
    onRendered: (List<Long>) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val effectiveBasemap = basemap ?: defaultBasemap()
    val currentPoints = rememberUpdatedState(points)
    val currentSelectedIdx = rememberUpdatedState(selectedPointIndex)
    val currentBottomInset = rememberUpdatedState(bottomInsetPx)
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    val selectedColor = MaterialTheme.colorScheme.tertiary.toArgb()

    val mapView = remember {
        PerfTrace.mark("MapView remember start")
        MapLibre.getInstance(context)
        val v = MapView(context).apply { onCreate(Bundle()) }
        PerfTrace.mark("MapView constructed")
        v
    }
    val lastAppliedBasemap = remember { arrayOf(effectiveBasemap) }
    // Track last rendered point list so we know whether to fit camera or just pan.
    val lastRenderedPointsKey = remember { arrayOf<List<Long>>(emptyList()) }

    DisposableEffect(lifecycleOwner, mapView) {
        // Track whether onDestroy already ran so the lifecycle observer and
        // the onDispose block don't both call it. MapLibre's MapView crashes
        // (NPE or "Map has been destroyed") on a second onDestroy when the
        // user back-navigates: ON_DESTROY fires first, then onDispose tries
        // to destroy it again as the composable leaves composition.
        var destroyed = false
        val safeDestroy = {
            if (!destroyed) {
                destroyed = true
                runCatching { mapView.onDestroy() }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> safeDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            safeDestroy()
        }
    }

    LaunchedEffect(effectiveBasemap) {
        if (lastAppliedBasemap[0] == effectiveBasemap) return@LaunchedEffect
        lastAppliedBasemap[0] = effectiveBasemap
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromBasemap(effectiveBasemap, context)) { style ->
                installLayers(style, lineColor, selectedColor)
                val ordered = currentPoints.value.sortedBy { it.timestampMs }
                renderPath(map, style, ordered, fitCamera = false, bottomInsetPx = currentBottomInset.value)
                renderSelectedPoint(map, style, ordered, currentSelectedIdx.value, panCamera = false)
            }
        }
    }

    AndroidView(
        factory = { _ ->
            PerfTrace.mark("HistoryMapView AndroidView factory")
            mapView.getMapAsync { map ->
                PerfTrace.mark("getMapAsync first callback")
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.setStyle(Style.Builder().fromBasemap(effectiveBasemap, context)) { style ->
                    PerfTrace.mark("setStyle loaded")
                    installLayers(style, lineColor, selectedColor)
                    val ordered = currentPoints.value.sortedBy { it.timestampMs }
                    val key = ordered.map { it.timestampMs }
                    lastRenderedPointsKey[0] = key
                    renderPath(map, style, ordered, fitCamera = true, bottomInsetPx = currentBottomInset.value)
                    renderSelectedPoint(map, style, ordered, currentSelectedIdx.value, panCamera = false)
                    onRendered(key)
                    PerfTrace.mark("first renderPath done points=${ordered.size}")
                }
            }
            mapView
        },
        update = { _ ->
            val ordered = points.sortedBy { it.timestampMs }
            val sel = selectedPointIndex
            val visible = routeVisible
            val inset = bottomInsetPx
            val newKey = ordered.map { it.timestampMs }
            // Fire synchronously with the new keys so callers know Compose
            // delivered the points to the updater; the async getMapAsync block
            // is what then does the actual MapLibre layer update.
            onRendered(newKey)
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                if (!style.isFullyLoaded) return@getMapAsync
                val pointsChanged = newKey != lastRenderedPointsKey[0]
                if (pointsChanged) {
                    lastRenderedPointsKey[0] = newKey
                    renderPath(map, style, ordered, fitCamera = true, bottomInsetPx = inset)
                }
                renderSelectedPoint(map, style, ordered, sel, panCamera = !pointsChanged)
                style.getLayer(PATH_LAYER)?.setProperties(
                    PropertyFactory.visibility(
                        if (visible) Property.VISIBLE else Property.NONE,
                    ),
                )
            }
        },
        modifier = modifier,
    )
}

private fun installLayers(style: Style, lineColorArgb: Int, selectedColorArgb: Int) {
    if (style.getSource(PATH_SOURCE) == null) {
        style.addSource(GeoJsonSource(PATH_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            LineLayer(PATH_LAYER, PATH_SOURCE).withProperties(
                PropertyFactory.lineColor(lineColorArgb),
                PropertyFactory.lineWidth(POLYLINE_WIDTH_DP),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }
    if (style.getSource(ENDPOINTS_SOURCE) == null) {
        style.addSource(GeoJsonSource(ENDPOINTS_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            CircleLayer(ENDPOINTS_LAYER, ENDPOINTS_SOURCE).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(lineColorArgb),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            )
        )
    }
    if (style.getSource(SELECTED_SOURCE) == null) {
        style.addSource(GeoJsonSource(SELECTED_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            CircleLayer(SELECTED_LAYER, SELECTED_SOURCE).withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor(selectedColorArgb),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            )
        )
    }
}

private fun renderPath(
    map: MapLibreMap,
    style: Style,
    points: List<HistoryPoint>,
    fitCamera: Boolean,
    bottomInsetPx: Int = 0,
) {
    val pathSource = style.getSourceAs<GeoJsonSource>(PATH_SOURCE) ?: return
    val endpointsSource = style.getSourceAs<GeoJsonSource>(ENDPOINTS_SOURCE) ?: return

    if (points.isEmpty()) {
        pathSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        endpointsSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }

    val geoPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    val latLngs = points.map { LatLng(it.latitude, it.longitude) }

    pathSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(geoPoints)))

    val endpointFeatures = buildList {
        add(Feature.fromGeometry(geoPoints.first()).apply { addStringProperty(PROP_ROLE, ROLE_START) })
        if (geoPoints.size > 1) {
            add(Feature.fromGeometry(geoPoints.last()).apply { addStringProperty(PROP_ROLE, ROLE_END) })
        }
    }
    endpointsSource.setGeoJson(FeatureCollection.fromFeatures(endpointFeatures))

    if (fitCamera) {
        // Center on the first point if we have only one OR if every point
        // shares the exact same coordinate. LatLngBounds.build() throws on
        // a degenerate (zero-area) box on some MapLibre Android versions.
        val distinct = latLngs.distinct()
        if (distinct.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(distinct.first(), 14.0))
        } else {
            val bounds = runCatching {
                LatLngBounds.Builder().apply { distinct.forEach { include(it) } }.build()
            }.getOrNull() ?: return
            // Asymmetric padding: keep the small uniform breathing room
            // on top / left / right and add the sheet's peek height as
            // bottom padding so points which would otherwise sit behind
            // the sheet stay visible above it.
            val updated = CameraUpdateFactory.newLatLngBounds(
                bounds,
                BOUNDS_PADDING_PX,
                BOUNDS_PADDING_PX,
                BOUNDS_PADDING_PX,
                BOUNDS_PADDING_PX + bottomInsetPx,
            )
            map.animateCamera(
                updated,
                400,
                object : MapLibreMap.CancelableCallback {
                    override fun onCancel() = Unit
                    override fun onFinish() {
                        if (map.cameraPosition.zoom > 17.0) {
                            map.animateCamera(CameraUpdateFactory.zoomTo(17.0))
                        }
                    }
                },
            )
        }
    }
}

private fun renderSelectedPoint(
    map: MapLibreMap,
    style: Style,
    orderedPoints: List<HistoryPoint>,
    selectedIdx: Int?,
    panCamera: Boolean,
) {
    val source = style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE) ?: return
    val pt = selectedIdx?.let { orderedPoints.getOrNull(it) }
    if (pt == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    val geoPoint = Point.fromLngLat(pt.longitude, pt.latitude)
    source.setGeoJson(Feature.fromGeometry(geoPoint))
    if (panCamera) {
        val zoom = map.cameraPosition.zoom.coerceAtLeast(13.0)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pt.latitude, pt.longitude), zoom))
    }
}
