package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.data.model.UserMapCameraPosition

/**
 * Web map: MapLibre GL behind the Compose canvas (see [MapSurface]), with the
 * same Compose chip markers, north button and camera behavior as Android.
 * MapLibre draws tiles and accuracy circles; markers are composables drawn at
 * the projected position of each tag, re-projected on every camera move.
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
    var map by remember { mutableStateOf<WebMap?>(null) }
    var ready by remember { mutableStateOf(false) }
    var cameraTick by remember { mutableIntStateOf(0) }
    var bearing by remember { mutableDoubleStateOf(0.0) }
    val lastStyle = remember { arrayOf(basemap) }
    // Compose lays out in device pixels, MapLibre works in CSS pixels.
    val density = LocalDensity.current.density

    Box(modifier = modifier.fillMaxSize()) {
        MapSurface(map = map, modifier = Modifier.fillMaxSize())

        val m = map
        if (m != null && ready) {
            @Suppress("UNUSED_VARIABLE")
            val tick = cameraTick
            val selected = markers.firstOrNull { it.beaconId == selectedBeaconId }
            for (marker in markers.filter { it.beaconId != selectedBeaconId } + listOfNotNull(selected)) {
                val (x, y) = m.project(marker.latitude, marker.longitude)
                ChipMarker(
                    marker = marker,
                    isSelected = marker.beaconId == selectedBeaconId,
                    onClick = { onMarkerClick(marker.beaconId) },
                    screenX = (x * density).toFloat(),
                    screenY = (y * density).toFloat(),
                )
            }
        }

        NorthLockButton(
            bearing = bearing.toFloat(),
            onReset = { map?.resetNorth() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 72.dp, end = 12.dp),
        )
    }

    DisposableEffect(Unit) {
        val camera = initialCamera?.takeIf { it.zoom >= MEANINGFUL_ZOOM_FLOOR }
        val created = WebMap(
            lat = camera?.lat ?: 0.0,
            lon = camera?.lon ?: 0.0,
            zoom = camera?.zoom?.toDouble() ?: 2.0,
            style = basemap.webStyle(),
            overlays = markerAccuracyOverlays,
            onReady = { ready = true; cameraTick++ },
            onMove = { b -> bearing = b; cameraTick++ },
            onIdle = { lat, lon, zoom ->
                cameraTick++
                onCameraIdle(UserMapCameraPosition(zoom = zoom.toFloat(), lat = lat, lon = lon))
            },
        )
        map = created
        onDispose {
            created.destroy()
            map = null
        }
    }

    // Everything below re-sends its state once the map has loaded, since the
    // bridge drops calls made before that.
    LaunchedEffect(ready, bottomInsetPx) {
        map?.setBottomPadding(bottomInsetPx / density.toDouble())
    }
    LaunchedEffect(ready, markers) {
        map?.setData("accuracy", accuracyGeoJson(markers))
    }
    LaunchedEffect(basemap) {
        if (lastStyle[0] == basemap) return@LaunchedEffect
        lastStyle[0] = basemap
        map?.setStyle(basemap.webStyle())
    }

    // Follow the selection, as on Android: only when the selected tag or its
    // position changes, zooming to at least street level.
    val selectedMarker = markers.firstOrNull { it.beaconId == selectedBeaconId }
    val cameraKey = selectedMarker?.let { "${it.beaconId}|${it.latitude}|${it.longitude}" }
    LaunchedEffect(ready, cameraKey) {
        val target = selectedMarker ?: return@LaunchedEffect
        val m = map?.takeIf { ready } ?: return@LaunchedEffect
        m.easeTo(
            lat = target.latitude,
            lon = target.longitude,
            zoom = maxOf(m.zoom, DEFAULT_FOCUS_ZOOM),
            durationMs = cameraDurationFor(haversineMeters(m.centerLat, m.centerLon, target.latitude, target.longitude)),
        )
    }
}
