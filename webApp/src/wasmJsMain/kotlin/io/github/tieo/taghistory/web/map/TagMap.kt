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
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import io.github.tieo.taghistory.ui.map.ACCURACY_COLOR
import io.github.tieo.taghistory.ui.map.BeaconMarkerUi
import io.github.tieo.taghistory.ui.map.DEFAULT_FOCUS_ZOOM
import io.github.tieo.taghistory.ui.map.MEANINGFUL_ZOOM_FLOOR
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.cameraDurationFor
import io.github.tieo.taghistory.ui.map.circleRing
import io.github.tieo.taghistory.ui.map.haversineMeters

/** Room panels take over a map, in CSS pixels; the camera keeps targets out from under them. */
data class MapInsets(val top: Int = 0, val right: Int = 0, val bottom: Int = 0, val left: Int = 0)

/**
 * The tag map: a chip per tag at its newest fix with its accuracy circle, the
 * selected tag raised and followed by the camera.
 */
@Composable
fun IComponent.tagMap(
    markers: List<BeaconMarkerUi>,
    selectedId: String?,
    initialCamera: UserMapCameraPosition?,
    basemap: MapBasemap,
    insets: MapInsets,
    onMarkerClick: (String) -> Unit,
    onCameraIdle: (UserMapCameraPosition) -> Unit,
    className: String = "absolute inset-0",
) {
    val clickTag by rememberUpdatedState(onMarkerClick)
    val idle by rememberUpdatedState(onCameraIdle)
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    // MapLibre makes its container position: relative, so it gets a child of
    // the positioned box rather than the box itself.
    div(className) {
        div("h-full w-full") {
        val container = element
        DisposableEffect(Unit) {
            val start = initialCamera?.takeIf { it.zoom >= MEANINGFUL_ZOOM_FLOOR }
            var mounted: MapHandle? = null
            mounted = mount(
                container,
                start?.lat ?: 30.0,
                start?.lon ?: 10.0,
                start?.zoom?.toDouble() ?: 1.5,
                styleFor(basemap),
                ACCURACY_OVERLAYS,
                onReady = { handle = mounted },
                onIdle = { lat, lon, zoom -> idle(UserMapCameraPosition(zoom.toFloat(), lat, lon)) },
                onClick = { _, _, _, _ -> },
            )
            onDispose {
                handle = null
                destroy(mounted)
            }
        }
        }
    }
    val h = handle ?: return
    LaunchedEffect(h, basemap) { setStyle(h, styleFor(basemap)) }
    LaunchedEffect(h, insets) { setPadding(h, insets.top.toDouble(), insets.right.toDouble(), insets.bottom.toDouble(), insets.left.toDouble()) }
    LaunchedEffect(h, markers, selectedId) {
        setMarkers(h, markersJson(markers), selectedId) { clickTag(it) }
        setData(h, ACCURACY_SOURCE, accuracyGeoJson(markers, selectedId))
    }
    // Follow the selection: when it changes, and when the selected tag moves.
    val selected = markers.firstOrNull { it.beaconId == selectedId }
    var lastFocus by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(h, selectedId, selected?.latitude, selected?.longitude) {
        if (selected == null) return@LaunchedEffect
        val target = selected.latitude to selected.longitude
        val from = lastFocus ?: target
        val distance = haversineMeters(from.first, from.second, target.first, target.second)
        easeTo(h, target.first, target.second, maxOf(zoom(h), DEFAULT_FOCUS_ZOOM), cameraDurationFor(distance))
        lastFocus = target
    }
}

private const val ACCURACY_SOURCE = "accuracy"

private val ACCURACY_OVERLAYS = """
{"sources": ["$ACCURACY_SOURCE"], "layers": [
  {"id": "accuracy-fill", "type": "fill", "source": "$ACCURACY_SOURCE",
   "paint": {"fill-color": "$ACCURACY_COLOR", "fill-opacity": ["case", ["get", "selected"], 0.18, 0.08]}},
  {"id": "accuracy-line", "type": "line", "source": "$ACCURACY_SOURCE",
   "paint": {"line-color": "$ACCURACY_COLOR", "line-width": 1.5, "line-opacity": ["case", ["get", "selected"], 0.9, 0.35]}}
]}
""".trimIndent()

private fun accuracyGeoJson(markers: List<BeaconMarkerUi>, selectedId: String?): String =
    markers.filter { it.horizontalAccuracy > 0 }.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") { m ->
        val ring = circleRing(m.latitude, m.longitude, m.horizontalAccuracy.toDouble(), 64)
            .joinToString(",") { (lon, lat) -> "[$lon,$lat]" }
        "{\"type\":\"Feature\",\"properties\":{\"selected\":${m.beaconId == selectedId}}," +
            "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}}"
    }

private fun markersJson(markers: List<BeaconMarkerUi>): String =
    markers.joinToString(",", "[", "]") { m ->
        "{\"id\":${jsonString(m.beaconId)},\"lat\":${m.latitude},\"lon\":${m.longitude}," +
            "\"label\":${jsonString(m.displayName)},\"emoji\":${m.emoji?.let(::jsonString) ?: "null"}}"
    }
