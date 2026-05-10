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
     * Pixels of the map area that are obscured by an overlay at the
     * top — status bar, notch, and the back / route-toggle / basemap
     * buttons floating in that strip. Excluded from the camera fit so
     * a point that would otherwise sit under the notch is padded down
     * into the visible slice.
     */
    topInsetPx: Int = 0,
    /**
     * Pixels of the map area that are obscured by an overlay at the
     * bottom (typically the sheet at peek). Excluded from the camera
     * fit the same way as [topInsetPx].
     */
    bottomInsetPx: Int = 0,
    /**
     * Fires when the user taps near a rendered history dot. The
     * argument is the [HistoryPoint.id] of the closest dot under the
     * tap; HistoryScreen routes that into selectedPointId so the row +
     * map highlight stay in sync.
     */
    onPointSelected: (String) -> Unit = {},
    onRendered: (timestamps: List<Long>) -> Unit = {},
    modifier: Modifier = Modifier,
)
