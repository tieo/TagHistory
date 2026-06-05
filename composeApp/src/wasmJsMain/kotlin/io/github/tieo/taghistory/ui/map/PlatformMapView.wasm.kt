package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import kotlinx.browser.window

/**
 * Web map fallback. Real maplibre-gl-js interop is significant work;
 * meanwhile this renders the marker list with click-through to
 * OpenStreetMap so the user can still see where each tag is.
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (markers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No located tags yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
            ) {
                items(markers, key = { it.beaconId }) { marker ->
                    Surface(
                        onClick = { onMarkerClick(marker.beaconId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = if (marker.beaconId == selectedBeaconId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "${marker.emoji ?: "📍"} ${marker.displayName}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${marker.latitude}, ${marker.longitude}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Open in OpenStreetMap",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val lat = marker.latitude
                                    val lon = marker.longitude
                                    window.open(
                                        "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon",
                                        "_blank",
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
