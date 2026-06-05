package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.data.model.UserMapCameraPosition

/**
 * Web PlatformMapView. Mounts a MapLibre canvas in a DOM div that
 * tracks the Compose surface's bounds via onGloballyPositioned. Map
 * is positioned fixed in the page so it visually sits in the same
 * box the Compose composable claims; tap routing goes through
 * maplibre's `click` event and back through `onMarkerClick`.
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
    val mapHandle = remember { MapHandle() }
    var bounds by remember { mutableStateOf(Rect.Zero) }

    DisposableEffect(Unit) {
        mapHandle.create(initialCamera, basemap) { id -> onMarkerClick(id) }
        onDispose { mapHandle.destroy() }
    }

    LaunchedEffect(bounds) {
        mapHandle.setBounds(
            bounds.left.toDouble(),
            bounds.top.toDouble(),
            bounds.width.toDouble(),
            bounds.height.toDouble(),
        )
    }
    LaunchedEffect(markers, selectedBeaconId) {
        mapHandle.setMarkers(markers, selectedBeaconId)
    }
    LaunchedEffect(basemap) {
        mapHandle.setBasemap(basemap)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                bounds = Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
            },
    ) {
        if (markers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No located tags yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * Thin wrapper around the JS MapLibre instance. All actual DOM /
 * map work happens in WebMap.js — Kotlin keeps a single js-object
 * handle and forwards calls.
 */
private class MapHandle {
    private var handle: JsAny? = null

    fun create(
        initialCamera: UserMapCameraPosition?,
        basemap: MapBasemap,
        onMarkerClick: (String) -> Unit,
    ) {
        handle = jsCreate(
            initialCamera?.lat ?: 0.0,
            initialCamera?.lon ?: 0.0,
            initialCamera?.zoom?.toDouble() ?: 2.0,
            basemap.styleUrl(),
            onMarkerClick,
        )
    }

    fun destroy() {
        handle?.let { jsDestroy(it) }
        handle = null
    }

    fun setBounds(x: Double, y: Double, w: Double, h: Double) {
        handle?.let { jsSetBounds(it, x, y, w, h) }
    }

    fun setMarkers(markers: List<BeaconMarkerUi>, selectedId: String?) {
        handle?.let { jsSetMarkers(it, encodeMarkers(markers, selectedId)) }
    }

    fun setBasemap(basemap: MapBasemap) {
        handle?.let { jsSetStyle(it, basemap.styleUrl()) }
    }

    private fun MapBasemap.styleUrl(): String = when (this) {
        MapBasemap.LIGHT -> "https://demotiles.maplibre.org/style.json"
        MapBasemap.DARK -> "https://api.maptiler.com/maps/dataviz-dark/style.json?key=missing"
        MapBasemap.SATELLITE -> "https://api.maptiler.com/maps/hybrid/style.json?key=missing"
    }

    private fun encodeMarkers(markers: List<BeaconMarkerUi>, selectedId: String?): String =
        markers.joinToString("|") { m ->
            "${m.beaconId},${m.latitude},${m.longitude},${m.emoji ?: "📍"},${if (m.beaconId == selectedId) 1 else 0}"
        }
}

private fun jsCreate(
    lat: Double,
    lon: Double,
    zoom: Double,
    styleUrl: String,
    onMarkerClick: (String) -> Unit,
): JsAny = js(
    "window.__taghistoryMap__.create(lat, lon, zoom, styleUrl, onMarkerClick)"
)

private fun jsDestroy(handle: JsAny) {
    js("window.__taghistoryMap__.destroy(handle)")
}

private fun jsSetBounds(handle: JsAny, x: Double, y: Double, w: Double, h: Double) {
    js("window.__taghistoryMap__.setBounds(handle, x, y, w, h)")
}

private fun jsSetMarkers(handle: JsAny, encoded: String) {
    js("window.__taghistoryMap__.setMarkers(handle, encoded)")
}

private fun jsSetStyle(handle: JsAny, styleUrl: String) {
    js("window.__taghistoryMap__.setStyle(handle, styleUrl)")
}
