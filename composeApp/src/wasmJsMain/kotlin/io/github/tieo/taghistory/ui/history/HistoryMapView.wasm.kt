package io.github.tieo.taghistory.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.ui.map.MapBasemap

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
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "History map placeholder · ${points.size} points",
            modifier = Modifier.padding(16.dp),
        )
    }
}
