package io.github.tieo.taghistory.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tieo.taghistory.ui.map.MapBasemap

@Composable
actual fun HistoryMapView(
    points: List<HistoryPoint>,
    selectedPointIndex: Int?,
    basemap: MapBasemap?,
    routeVisible: Boolean,
    bottomInsetPx: Int,
    onRendered: (List<Long>) -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "iOS map not yet implemented. ${points.size} history point(s).",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
