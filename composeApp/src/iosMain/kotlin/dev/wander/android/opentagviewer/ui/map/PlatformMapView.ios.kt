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
 * iOS stub. A real MKMapView binding will land when we ship an iOS
 * platform host — until then, the compile target just needs something
 * that satisfies the expect declaration so shared code builds.
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
            "iOS map not yet implemented — ${markers.size} beacon(s) loaded.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
