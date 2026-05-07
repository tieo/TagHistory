package io.github.tieo.taghistory.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tieo.taghistory.ui.map.MapBasemap

@Composable
expect fun HistoryMapView(
    points: List<HistoryPoint>,
    selectedPointIndex: Int? = null,
    basemap: MapBasemap? = null,
    modifier: Modifier = Modifier,
)
