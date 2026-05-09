package io.github.tieo.taghistory.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tieo.taghistory.ui.map.MapBasemap

@Composable
expect fun HistoryMapView(
    points: List<HistoryPoint>,
    selectedPointIndex: Int? = null,
    basemap: MapBasemap? = null,
    /**
     * Toggles the connecting polyline. When false, the start / end /
     * selected dots are still drawn — only the line is hidden. Useful
     * when the route would clutter the map but the user still needs to
     * see the stops.
     */
    routeVisible: Boolean = true,
    /**
     * Pixels of the map area that are obscured by an overlay (typically
     * the bottom sheet at peek). Used as bottom padding when fitting
     * the camera so that points which would otherwise sit behind the
     * sheet are still visible above it.
     */
    bottomInsetPx: Int = 0,
    onRendered: (timestamps: List<Long>) -> Unit = {},
    modifier: Modifier = Modifier,
)
