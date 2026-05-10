package io.github.tieo.taghistory.ui.history

import android.graphics.Color
import android.graphics.RectF
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
import androidx.compose.ui.platform.LocalDensity
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
import org.maplibre.android.style.layers.SymbolLayer
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
private const val LABELS_SOURCE = "history-labels"
private const val LABELS_LAYER = "history-labels-symbol"
private const val ALL_DOTS_SOURCE = "history-all-dots"
private const val ALL_DOTS_LAYER = "history-all-dots-hit"
private const val PROP_ROLE = "role"
private const val PROP_LABEL = "label"
private const val PROP_POINT_ID = "point_id"
private const val ROLE_START = "start"
private const val ROLE_END = "end"
private const val ROLE_SELECTED = "selected"
private const val MAX_LABEL_CHARS = 28

@Composable
actual fun HistoryMapView(
    points: List<HistoryPoint>,
    selectedPointIndex: Int?,
    basemap: MapBasemap?,
    routeVisible: Boolean,
    topInsetPx: Int,
    bottomInsetPx: Int,
    onPointSelected: (String) -> Unit,
    onRendered: (List<Long>) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val effectiveBasemap = basemap ?: defaultBasemap()
    val currentPoints = rememberUpdatedState(points)
    val currentSelectedIdx = rememberUpdatedState(selectedPointIndex)
    val currentTopInset = rememberUpdatedState(topInsetPx)
    val currentBottomInset = rememberUpdatedState(bottomInsetPx)
    val currentOnPointSelected = rememberUpdatedState(onPointSelected)
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    val selectedColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    // Tap-test radius in pixels — generous because dots are 12 dp.
    val tapRadiusPx = 24f * density.density

    val mapView = remember {
        PerfTrace.mark("MapView remember start")
        MapLibre.getInstance(context)
        val v = MapView(context).apply { onCreate(Bundle()) }
        PerfTrace.mark("MapView constructed")
        v
    }
    val lastAppliedBasemap = remember { arrayOf(effectiveBasemap) }
    val lastRenderedPointsKey = remember { arrayOf<List<Long>>(emptyList()) }

    DisposableEffect(lifecycleOwner, mapView) {
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
                installLayers(style, lineColor, selectedColor, onSurfaceColor, surfaceColor)
                val ordered = currentPoints.value.sortedBy { it.timestampMs }
                renderPath(map, style, ordered, fitCamera = false, topInsetPx = currentTopInset.value, bottomInsetPx = currentBottomInset.value)
                renderSelectedPoint(map, style, ordered, currentSelectedIdx.value, panCamera = false)
                renderLabels(style, ordered, currentSelectedIdx.value)
                renderAllDots(style, ordered)
            }
        }
    }

    AndroidView(
        factory = { _ ->
            PerfTrace.mark("HistoryMapView AndroidView factory")
            // Synchronously claim the first key so the very next
            // recomposition's `update` block sees a non-empty
            // lastRenderedPointsKey. Doing this inside the async
            // getMapAsync callback opens the same fitCamera-stacking
            // race we already fixed in update; lint catches that
            // pattern via :verifyNoAsyncMapStateWrite.
            val initialOrdered = currentPoints.value.sortedBy { it.timestampMs }
            val initialKey = initialOrdered.map { it.timestampMs }
            lastRenderedPointsKey[0] = initialKey
            mapView.getMapAsync { map ->
                PerfTrace.mark("getMapAsync first callback")
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isLogoEnabled = false

                map.addOnMapClickListener { latLng ->
                    val style = map.style
                    if (style == null || !style.isFullyLoaded) return@addOnMapClickListener false
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val rect = RectF(
                        screenPoint.x - tapRadiusPx,
                        screenPoint.y - tapRadiusPx,
                        screenPoint.x + tapRadiusPx,
                        screenPoint.y + tapRadiusPx,
                    )
                    val features = map.queryRenderedFeatures(rect, ALL_DOTS_LAYER)
                    if (features.isEmpty()) return@addOnMapClickListener false
                    // Pick the feature whose dot is closest in screen
                    // space; queryRenderedFeatures returns them in z-order
                    // which isn't necessarily what we want.
                    val best = features.minByOrNull { f ->
                        val geom = f.geometry() as? Point ?: return@minByOrNull Float.MAX_VALUE
                        val sp = map.projection.toScreenLocation(LatLng(geom.latitude(), geom.longitude()))
                        val dx = sp.x - screenPoint.x
                        val dy = sp.y - screenPoint.y
                        dx * dx + dy * dy
                    }
                    val id = best?.getStringProperty(PROP_POINT_ID)
                    if (id != null) {
                        currentOnPointSelected.value(id)
                        true
                    } else {
                        false
                    }
                }

                map.setStyle(Style.Builder().fromBasemap(effectiveBasemap, context)) { style ->
                    PerfTrace.mark("setStyle loaded")
                    installLayers(style, lineColor, selectedColor, onSurfaceColor, surfaceColor)
                    // Re-read currentPoints in case state.points changed
                    // between factory entry and style-loaded; render
                    // against the latest, but the synchronous key claim
                    // happened above so the update block won't double-fit.
                    val ordered = currentPoints.value.sortedBy { it.timestampMs }
                    val key = ordered.map { it.timestampMs }
                    renderPath(map, style, ordered, fitCamera = true, topInsetPx = currentTopInset.value, bottomInsetPx = currentBottomInset.value)
                    renderSelectedPoint(map, style, ordered, currentSelectedIdx.value, panCamera = false)
                    renderLabels(style, ordered, currentSelectedIdx.value)
                    renderAllDots(style, ordered)
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
            val topInset = topInsetPx
            val bottomInset = bottomInsetPx
            val newKey = ordered.map { it.timestampMs }
            // Detect the diff and commit the new key SYNCHRONOUSLY,
            // before queuing the async getMapAsync block. Two updates
            // arriving in quick succession (e.g. day-switch + a follow-up
            // VM emit during geocode patching) would otherwise both
            // observe the stale lastRenderedPointsKey, both fire
            // fitCamera, and stack two animateCamera calls — the
            // composed animation snapped the user past the intended
            // bounds and looked like a spurious zoom-in.
            val pointsChanged = newKey != lastRenderedPointsKey[0]
            if (pointsChanged) lastRenderedPointsKey[0] = newKey
            onRendered(newKey)
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                if (!style.isFullyLoaded) return@getMapAsync
                if (pointsChanged) {
                    renderPath(map, style, ordered, fitCamera = true, topInsetPx = topInset, bottomInsetPx = bottomInset)
                    renderAllDots(style, ordered)
                }
                renderSelectedPoint(map, style, ordered, sel, panCamera = !pointsChanged)
                renderLabels(style, ordered, sel)
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

private fun installLayers(
    style: Style,
    lineColorArgb: Int,
    selectedColorArgb: Int,
    labelColorArgb: Int,
    labelHaloArgb: Int,
) {
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
    if (style.getSource(ALL_DOTS_SOURCE) == null) {
        style.addSource(GeoJsonSource(ALL_DOTS_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        // Invisible but still hit-testable: zero opacity with a real
        // radius so queryRenderedFeatures returns it under the user's
        // tap. Stays under the visible endpoint / selected circles in
        // z-order (added first, can re-add if re-installed elsewhere).
        style.addLayer(
            CircleLayer(ALL_DOTS_LAYER, ALL_DOTS_SOURCE).withProperties(
                PropertyFactory.circleRadius(12f),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleStrokeOpacity(0f),
            )
        )
    }
    if (style.getSource(LABELS_SOURCE) == null) {
        style.addSource(GeoJsonSource(LABELS_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            SymbolLayer(LABELS_LAYER, LABELS_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get(PROP_LABEL)),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(labelColorArgb),
                PropertyFactory.textHaloColor(labelHaloArgb),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textOffset(arrayOf(0f, -1.4f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(false),
                PropertyFactory.textPadding(2f),
            )
        )
    }
}

private fun renderPath(
    map: MapLibreMap,
    style: Style,
    points: List<HistoryPoint>,
    fitCamera: Boolean,
    topInsetPx: Int = 0,
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
        val distinct = latLngs.distinct()
        if (distinct.size == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(distinct.first(), 14.0))
        } else {
            val bounds = runCatching {
                LatLngBounds.Builder().apply { distinct.forEach { include(it) } }.build()
            }.getOrNull() ?: return
            // Direct asymmetric fit. Pixel padding is per-edge,
            // [left, top, right, bottom]. The platform top inset
            // (status bar + floating buttons strip) and bottom inset
            // (sheet peek) keep the route inside the slice that's
            // actually visible. animateCamera always cancels any
            // in-flight animation, so rapid day-switches no longer
            // stack visible camera moves the way the previous
            // two-stage zoom-then-cap path did.
            val update = CameraUpdateFactory.newLatLngBounds(
                bounds,
                BOUNDS_PADDING_PX,
                BOUNDS_PADDING_PX + topInsetPx,
                BOUNDS_PADDING_PX,
                BOUNDS_PADDING_PX + bottomInsetPx,
            )
            map.animateCamera(update, 400)
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

/**
 * Floats a small text label over the start, end, and currently
 * selected point. Uses the resolved address when available, falling
 * back to a "HH:mm" timestamp so empty caches still get something
 * useful.
 */
private fun renderLabels(
    style: Style,
    orderedPoints: List<HistoryPoint>,
    selectedIdx: Int?,
) {
    val source = style.getSourceAs<GeoJsonSource>(LABELS_SOURCE) ?: return
    if (orderedPoints.isEmpty()) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    val first = orderedPoints.first()
    val last = orderedPoints.last()
    val selected = selectedIdx?.let { orderedPoints.getOrNull(it) }

    val features = buildList {
        add(first.toLabelFeature(role = ROLE_START))
        if (orderedPoints.size > 1) {
            add(last.toLabelFeature(role = ROLE_END))
        }
        if (selected != null && selected.id != first.id && selected.id != last.id) {
            add(selected.toLabelFeature(role = ROLE_SELECTED))
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun HistoryPoint.toLabelFeature(role: String): Feature {
    val rawLabel = address ?: formatLocalTime(timestampMs)
    val truncated = if (rawLabel.length <= MAX_LABEL_CHARS) rawLabel
                    else rawLabel.take(MAX_LABEL_CHARS - 1) + "…"
    return Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
        addStringProperty(PROP_LABEL, truncated)
        addStringProperty(PROP_ROLE, role)
    }
}

/**
 * Invisible-but-hit-testable circle per point so a tap anywhere near
 * a dot resolves back to its [HistoryPoint.id]. Kept separate from
 * the visible endpoint / selected layers so the styling above stays
 * unchanged.
 */
private fun renderAllDots(style: Style, orderedPoints: List<HistoryPoint>) {
    val source = style.getSourceAs<GeoJsonSource>(ALL_DOTS_SOURCE) ?: return
    if (orderedPoints.isEmpty()) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    val features = orderedPoints.map { p ->
        Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
            addStringProperty(PROP_POINT_ID, p.id)
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}
