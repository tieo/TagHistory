package io.github.tieo.taghistory.web.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.kilua.core.IComponent
import dev.kilua.html.div
import io.github.tieo.taghistory.ui.history.HistoryPoint
import io.github.tieo.taghistory.ui.history.cssHex
import io.github.tieo.taghistory.ui.history.historyLineColor
import io.github.tieo.taghistory.ui.history.historySelectedColor
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.circleRing
import kotlin.math.hypot

/**
 * One day of a tag's history on a map: the track, its first and last fix,
 * and the selected fix with its accuracy circle. A click near a fix selects
 * it. The camera fits the day when the day changes and follows the selection.
 */
@Composable
fun IComponent.historyMap(
    points: List<HistoryPoint>,
    selectedId: String?,
    basemap: MapBasemap,
    insets: MapInsets,
    onPointSelected: (String) -> Unit,
    className: String = "absolute inset-0",
) {
    val ordered = remember(points) { points.sortedBy { it.timestampMs } }
    val currentOrdered by rememberUpdatedState(ordered)
    val select by rememberUpdatedState(onPointSelected)
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    div(className) {
        div("h-full w-full") {
            val container = element
            // The layer colours depend on the basemap, so a basemap change
            // builds the map again.
            DisposableEffect(basemap) {
                var mounted: MapHandle? = null
                mounted = mount(
                    container,
                    ordered.firstOrNull()?.latitude ?: 30.0,
                    ordered.firstOrNull()?.longitude ?: 10.0,
                    if (ordered.isEmpty()) 1.5 else 14.0,
                    styleFor(basemap),
                    historyOverlays(basemap),
                    onReady = { handle = mounted },
                    onIdle = { _, _, _ -> },
                    onClick = { _, _, x, y ->
                        val h = mounted ?: return@mount
                        val nearest = currentOrdered.minByOrNull { p ->
                            hypot(projectX(h, p.latitude, p.longitude) - x, projectY(h, p.latitude, p.longitude) - y)
                        }
                        if (nearest != null &&
                            hypot(projectX(h, nearest.latitude, nearest.longitude) - x, projectY(h, nearest.latitude, nearest.longitude) - y) <= TAP_RADIUS_PX
                        ) {
                            select(nearest.id)
                        }
                    },
                )
                onDispose {
                    handle = null
                    destroy(mounted)
                }
            }
        }
    }
    val h = handle ?: return
    LaunchedEffect(h, insets) { setPadding(h, insets.top.toDouble(), insets.right.toDouble(), insets.bottom.toDouble(), insets.left.toDouble()) }
    LaunchedEffect(h, ordered) {
        setData(h, PATH, lineGeoJson(ordered))
        setData(h, ENDPOINTS, pointsGeoJson(listOfNotNull(ordered.firstOrNull(), ordered.lastOrNull().takeIf { ordered.size > 1 })))
        val distinct = ordered.map { it.latitude to it.longitude }.distinct()
        when {
            distinct.isEmpty() -> Unit
            distinct.size == 1 -> easeTo(h, distinct[0].first, distinct[0].second, 14.0, 400)
            else -> fitBounds(
                h,
                distinct.minOf { it.first },
                distinct.minOf { it.second },
                distinct.maxOf { it.first },
                distinct.maxOf { it.second },
                400,
            )
        }
    }
    var lastSelection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(h, ordered, selectedId) {
        val selected = ordered.firstOrNull { it.id == selectedId }
        setData(h, SELECTED, pointsGeoJson(listOfNotNull(selected)))
        setData(h, ACCURACY, accuracyGeoJson(selected))
        if (selected != null && selectedId != lastSelection) {
            easeTo(h, selected.latitude, selected.longitude, maxOf(zoom(h), 13.0), 400)
        }
        lastSelection = selectedId
    }
}

private const val TAP_RADIUS_PX = 24.0
private const val PATH = "history-path"
private const val ENDPOINTS = "history-endpoints"
private const val ACCURACY = "history-accuracy"
private const val SELECTED = "history-selected"

/** The same layers, colours and sizes as the Android app's history map. */
private fun historyOverlays(basemap: MapBasemap): String {
    val line = cssHex(historyLineColor(basemap))
    val selected = cssHex(historySelectedColor(basemap))
    return """
    {"sources": ["$PATH", "$ENDPOINTS", "$ACCURACY", "$SELECTED"], "layers": [
      {"id":"history-path-line","type":"line","source":"$PATH","layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"$line","line-width":4}},
      {"id":"history-endpoints-outer","type":"circle","source":"$ENDPOINTS","paint":{"circle-radius":7,"circle-color":"$line","circle-stroke-width":2,"circle-stroke-color":"#ffffff"}},
      {"id":"history-endpoints-inner","type":"circle","source":"$ENDPOINTS","paint":{"circle-radius":3,"circle-color":"#ffffff"}},
      {"id":"history-accuracy-fill","type":"fill","source":"$ACCURACY","paint":{"fill-color":"$selected","fill-opacity":0.12}},
      {"id":"history-accuracy-stroke","type":"line","source":"$ACCURACY","paint":{"line-color":"$selected","line-width":1.5,"line-opacity":0.6}},
      {"id":"history-selected-halo","type":"circle","source":"$SELECTED","paint":{"circle-radius":18,"circle-color":"$selected","circle-opacity":0.18}},
      {"id":"history-selected-core","type":"circle","source":"$SELECTED","paint":{"circle-radius":8,"circle-color":"$selected","circle-stroke-width":3,"circle-stroke-color":"#ffffff"}}
    ]}
    """.trimIndent()
}

private fun lineGeoJson(points: List<HistoryPoint>): String =
    if (points.size < 2) {
        "{\"type\":\"FeatureCollection\",\"features\":[]}"
    } else points.joinToString(",", "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[", "]}}") {
        "[${it.longitude},${it.latitude}]"
    }

private fun pointsGeoJson(points: List<HistoryPoint>): String =
    points.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") {
        "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${it.longitude},${it.latitude}]}}"
    }

private fun accuracyGeoJson(point: HistoryPoint?): String {
    if (point == null || point.horizontalAccuracy <= 0) return "{\"type\":\"FeatureCollection\",\"features\":[]}"
    val ring = circleRing(point.latitude, point.longitude, point.horizontalAccuracy.toDouble(), 64)
        .joinToString(",") { (lon, lat) -> "[$lon,$lat]" }
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}," +
        "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}}]}"
}
