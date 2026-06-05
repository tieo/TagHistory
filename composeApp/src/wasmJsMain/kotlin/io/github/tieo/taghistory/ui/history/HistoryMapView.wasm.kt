package io.github.tieo.taghistory.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.ui.map.MapBasemap
import kotlinx.browser.window

/**
 * Web history-map fallback. Like the main map view, swaps the
 * real maplibre canvas for a scrollable list with OpenStreetMap
 * click-throughs per point. Still calls `onRendered` so the
 * surrounding ViewModel's "map rendered" path stays in sync.
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
    LaunchedEffect(points) {
        onRendered(points.map { it.timestampMs })
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (points.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No history points",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(points.withIndex().toList(), key = { it.value.id }) { (idx, point) ->
                    Surface(
                        onClick = { onPointSelected(point.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        color = if (idx == selectedPointIndex) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        },
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                point.address ?: "${point.latitude}, ${point.longitude}",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "±${point.horizontalAccuracy} m · ${point.kind}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Open in OpenStreetMap",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val lat = point.latitude
                                    val lon = point.longitude
                                    window.open(
                                        "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon",
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
