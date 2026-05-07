package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tieo.taghistory.data.model.UserMapCameraPosition

/**
 * Desktop stub — no Google Maps binding on Compose Desktop yet. Shows a
 * marker count so the rest of the screen stays exercisable for layout
 * work. Slated for a proper tile-renderer port in Phase 15.
 */
@Composable
actual fun PlatformMapView(
    markers: List<BeaconMarkerUi>,
    selectedBeaconId: String?,
    initialCamera: UserMapCameraPosition?,
    basemap: MapBasemap,
    onMarkerClick: (String) -> Unit,
    onCameraIdle: (UserMapCameraPosition) -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Map not rendered on desktop — ${markers.size} beacon(s) loaded.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
