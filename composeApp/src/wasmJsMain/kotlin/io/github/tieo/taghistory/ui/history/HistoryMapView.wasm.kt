package io.github.tieo.taghistory.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.github.tieo.taghistory.ui.map.GeoJson
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.MapOverlays
import io.github.tieo.taghistory.ui.map.MapSurface
import io.github.tieo.taghistory.ui.map.WebMap
import io.github.tieo.taghistory.ui.map.defaultBasemap
import io.github.tieo.taghistory.ui.map.webStyle
import kotlin.math.hypot

/**
 * Web history map: the day's track as a line with start and end dots, the
 * selected point with a halo and its true-to-scale accuracy circle, styled
 * like Android's. A tap selects the nearest point within reach. The camera
 * fits the track when the points change and follows a new selection.
 */
@Composable
actual fun HistoryMapView(
    points: List<HistoryPoint>,
    selectedPointIndex: Int?,
    basemap: MapBasemap?,
    routeVisible: Boolean,
    topInsetPx: Int,
    bottomInsetPx: Int,
    onPointSelected: (String) -> Unit,
    onRendered: (timestamps: List<Long>) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current.density.toDouble()
    val effectiveBasemap = basemap ?: defaultBasemap()
    val ordered = remember(points) { points.sortedBy { it.timestampMs } }
    val currentOrdered by rememberUpdatedState(ordered)
    val currentOnPointSelected by rememberUpdatedState(onPointSelected)
    var map by remember { mutableStateOf<WebMap?>(null) }
    var ready by remember { mutableStateOf(false) }

    DisposableEffect(effectiveBasemap) {
        val created = WebMap(
            lat = ordered.firstOrNull()?.latitude ?: 0.0,
            lon = ordered.firstOrNull()?.longitude ?: 0.0,
            zoom = if (ordered.isEmpty()) 2.0 else 14.0,
            style = effectiveBasemap.webStyle(),
            overlays = historyOverlays(effectiveBasemap),
            onReady = { ready = true },
            onMove = {},
            onIdle = { _, _, _ -> },
        )
        map = created
        onDispose {
            ready = false
            created.destroy()
            map = null
        }
    }

    MapSurface(
        map = map,
        modifier = modifier,
        onTap = { x, y ->
            val m = map ?: return@MapSurface
            val nearest = currentOrdered.minByOrNull { p ->
                val (px, py) = m.project(p.latitude, p.longitude)
                hypot(px - x, py - y)
            } ?: return@MapSurface
            val (px, py) = m.project(nearest.latitude, nearest.longitude)
            if (hypot(px - x, py - y) <= TAP_RADIUS_PX) currentOnPointSelected(nearest.id)
        },
    )

    // Track, endpoints and the camera fit, whenever the day's points change.
    LaunchedEffect(ready, ordered) {
        onRendered(ordered.map { it.timestampMs })
        val m = map?.takeIf { ready } ?: return@LaunchedEffect
        m.setData(PATH, GeoJson.line(ordered.map { it.latitude to it.longitude }))
        m.setData(
            ENDPOINTS,
            GeoJson.collection(listOfNotNull(ordered.firstOrNull(), ordered.lastOrNull().takeIf { ordered.size > 1 })
                .map { GeoJson.point(it.latitude, it.longitude) }),
        )
        val distinct = ordered.map { it.latitude to it.longitude }.distinct()
        when {
            distinct.isEmpty() -> Unit
            distinct.size == 1 -> m.easeTo(distinct[0].first, distinct[0].second, zoom = 14.0, durationMs = 400)
            else -> m.fitBounds(
                south = distinct.minOf { it.first },
                west = distinct.minOf { it.second },
                north = distinct.maxOf { it.first },
                east = distinct.maxOf { it.second },
                topCss = topInsetPx / density,
                bottomCss = bottomInsetPx / density,
                durationMs = 400,
            )
        }
    }

    // The selected point; the camera follows only a selection change, not
    // a new day (the fit above handles that).
    val lastSelection = remember { arrayOf<Int?>(-1) }
    LaunchedEffect(ready, ordered, selectedPointIndex) {
        val m = map?.takeIf { ready } ?: return@LaunchedEffect
        val selected = selectedPointIndex?.let { ordered.getOrNull(it) }
        m.setData(SELECTED, GeoJson.collection(listOfNotNull(selected?.let { GeoJson.point(it.latitude, it.longitude) })))
        m.setData(
            ACCURACY,
            GeoJson.collection(listOfNotNull(selected?.takeIf { it.horizontalAccuracy > 0 }?.let {
                GeoJson.circle(it.latitude, it.longitude, it.horizontalAccuracy.toDouble())
            })),
        )
        val selectionChanged = lastSelection[0] != selectedPointIndex && lastSelection[0] != -1
        lastSelection[0] = selectedPointIndex
        if (selected != null && selectionChanged) {
            m.easeTo(selected.latitude, selected.longitude, zoom = maxOf(m.zoom, 13.0), durationMs = 400)
        }
    }

    LaunchedEffect(ready, routeVisible) {
        map?.takeIf { ready }?.setLayerVisible(PATH_LAYER, routeVisible)
    }
}

private const val TAP_RADIUS_PX = 24.0
private const val PATH = "history-path"
private const val PATH_LAYER = "history-path-line"
private const val ENDPOINTS = "history-endpoints"
private const val ACCURACY = "history-accuracy"
private const val SELECTED = "history-selected"

/** The same layers, colors and sizes as Android's history map. */
private fun historyOverlays(basemap: MapBasemap): MapOverlays {
    val line = cssHex(historyLineColor(basemap))
    val selected = cssHex(historySelectedColor(basemap))
    return MapOverlays(
        sources = listOf(PATH, ENDPOINTS, ACCURACY, SELECTED),
        layers = listOf(
            """{"id":"$PATH_LAYER","type":"line","source":"$PATH","layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"$line","line-width":4}}""",
            """{"id":"history-endpoints-outer","type":"circle","source":"$ENDPOINTS","paint":{"circle-radius":7,"circle-color":"$line","circle-stroke-width":2,"circle-stroke-color":"#ffffff"}}""",
            """{"id":"history-endpoints-inner","type":"circle","source":"$ENDPOINTS","paint":{"circle-radius":3,"circle-color":"#ffffff"}}""",
            """{"id":"history-accuracy-fill","type":"fill","source":"$ACCURACY","paint":{"fill-color":"$selected","fill-opacity":0.12}}""",
            """{"id":"history-accuracy-stroke","type":"line","source":"$ACCURACY","paint":{"line-color":"$selected","line-width":1.5,"line-opacity":0.6}}""",
            """{"id":"history-selected-halo","type":"circle","source":"$SELECTED","paint":{"circle-radius":18,"circle-color":"$selected","circle-opacity":0.18}}""",
            """{"id":"history-selected-core","type":"circle","source":"$SELECTED","paint":{"circle-radius":8,"circle-color":"$selected","circle-stroke-width":3,"circle-stroke-color":"#ffffff"}}""",
        ),
    )
}
