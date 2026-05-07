package io.github.tieo.taghistory.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Google Maps Timeline-style path view: draws the day's history points as
 * a polyline with start/end emphasis. [selectedPointIndex] highlights the
 * scrubber's current position and pans the camera to it.
 */
@Composable
expect fun HistoryMapView(
    points: List<HistoryPoint>,
    selectedPointIndex: Int? = null,
    modifier: Modifier = Modifier,
)
