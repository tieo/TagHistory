package io.github.tieo.taghistory.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tieo.taghistory.data.model.UserMapCameraPosition

/**
 * Host-provided map surface. Android implements with Google Maps (via
 * `AndroidView` + `MapView`), desktop shows a placeholder, iOS gets a
 * similar placeholder until MapKit-compose lands.
 *
 * Intentionally dumb: state snapshot in, user events out. The ViewModel
 * owns selection + camera persistence, so the platform widget only
 * forwards callbacks — it does not cache markers or camera values.
 */
@Composable
expect fun PlatformMapView(
    markers: List<BeaconMarkerUi>,
    selectedBeaconId: String?,
    initialCamera: UserMapCameraPosition?,
    basemap: MapBasemap,
    onMarkerClick: (String) -> Unit,
    onCameraIdle: (UserMapCameraPosition) -> Unit,
    modifier: Modifier = Modifier,
)
