package io.github.tieo.taghistory.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.tieo.taghistory.ui.map.BasemapCycleButton
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.defaultBasemap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    reverseGeocode: (suspend (Double, Double) -> String?)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val end = Clock.System.now().toEpochMilliseconds()
        // Show cached data immediately so the screen isn't blank for 15s while
        // the network fetch runs.
        viewModel.load(end - 7L * DAY_MS, end)
        viewModel.fetchAndLoad(end - 7L * DAY_MS, end)
    }

    val days = remember(state.points) { buildDayBuckets(state.points) }
    var dayIdx by remember(days) { mutableIntStateOf(0) }
    val selectedDay = days.getOrNull(dayIdx)

    val chronological = remember(selectedDay) {
        selectedDay?.points?.sortedBy { it.timestampMs } ?: emptyList()
    }

    var selectedPointIdx by remember(chronological) {
        mutableIntStateOf((chronological.size - 1).coerceAtLeast(0))
    }

    val addressCache = remember { mutableStateMapOf<Long, String>() }
    if (reverseGeocode != null) {
        LaunchedEffect(state.points) {
            for (point in state.points) {
                if (!addressCache.containsKey(point.timestampMs)) {
                    val address = reverseGeocode(point.latitude, point.longitude)
                    if (address != null) addressCache[point.timestampMs] = address
                }
            }
        }
    }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    val themeDefault = defaultBasemap()
    var basemap by remember(themeDefault) { mutableStateOf(themeDefault) }

    var lastRenderedCount by remember { mutableIntStateOf(-1) }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedPointIdx, chronological.size) {
        if (chronological.isNotEmpty()) {
            val listIdx = chronological.size - 1 - selectedPointIdx
            listState.animateScrollToItem(listIdx)
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = modifier.fillMaxSize(),
        sheetPeekHeight = 240.dp,
        sheetContent = {
            SheetContent(
                days = days,
                dayIdx = dayIdx,
                chronological = chronological,
                selectedPointIdx = selectedPointIdx,
                isLoading = state.isLoading,
                error = state.error,
                listState = listState,
                addressCache = addressCache,
                lastRenderedCount = lastRenderedCount,
                onDayPrev = { if (dayIdx < days.size - 1) dayIdx++ },
                onDayNext = { if (dayIdx > 0) dayIdx-- },
                onSelectPoint = { selectedPointIdx = it },
                onRetry = {
                    val end = Clock.System.now().toEpochMilliseconds()
                    viewModel.fetchAndLoad(end - 7L * DAY_MS, end)
                },
            )
        },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            HistoryMapView(
                points = chronological,
                selectedPointIndex = selectedPointIdx,
                basemap = basemap,
                onRendered = { lastRenderedCount = it.size },
                modifier = Modifier.fillMaxSize(),
            )


            FilledIconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 12.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            BasemapCycleButton(
                current = basemap,
                onCycle = {
                    basemap = when (basemap) {
                        MapBasemap.LIGHT -> MapBasemap.DARK
                        MapBasemap.DARK -> MapBasemap.SATELLITE
                        MapBasemap.SATELLITE -> MapBasemap.LIGHT
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun SheetContent(
    days: List<DayBucket>,
    dayIdx: Int,
    chronological: List<HistoryPoint>,
    selectedPointIdx: Int,
    isLoading: Boolean,
    error: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    addressCache: Map<Long, String>,
    lastRenderedCount: Int,
    onDayPrev: () -> Unit,
    onDayNext: () -> Unit,
    onSelectPoint: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Test signal: testTag flips whenever the MapLibre layer actually
        // re-rendered. Lives in the bottom sheet (always reachable in the
        // semantics tree, never occluded by the map view).
        Box(
            modifier = Modifier
                .size(1.dp)
                .testTag("map_render_$lastRenderedCount")
                .semantics { contentDescription = "map_render_$lastRenderedCount" },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDayPrev,
                enabled = dayIdx < days.size - 1,
                modifier = Modifier.testTag("btn_day_prev"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
            }
            val now = Clock.System.now().toEpochMilliseconds()
            Text(
                text = days.getOrNull(dayIdx)?.key?.let { dayLabel(it, now) } ?: "No data",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = onDayNext,
                enabled = dayIdx > 0,
                modifier = Modifier.testTag("btn_day_next"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
            }
        }

        HorizontalDivider()

        if (chronological.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Timeline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${chronological.size} data point${if (chronological.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (error != null && chronological.isEmpty()) {
            ErrorBlock(message = error, onRetry = onRetry)
            return@Column
        }

        val reversed = remember(chronological) { chronological.asReversed() }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            itemsIndexed(reversed, key = { _, p -> p.timestampMs }) { listIdx, point ->
                val chronoIdx = chronological.size - 1 - listIdx
                val isSelected = chronoIdx == selectedPointIdx
                HistoryListItem(
                    point = point,
                    address = addressCache[point.timestampMs],
                    isSelected = isSelected,
                    testTag = "history_item_$listIdx",
                    onClick = { onSelectPoint(chronoIdx) },
                )
            }
        }
    }
}

@Composable
private fun HistoryListItem(
    point: HistoryPoint,
    address: String?,
    isSelected: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                address ?: "%.5f, %.5f".format(point.latitude, point.longitude),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "At ${formatLocalTime(point.timestampMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(
            onClick = onRetry,
            modifier = Modifier.testTag("btn_history_retry"),
        ) {
            Text("Retry")
        }
    }
}

@Composable
private fun FullScreenMessage(
    message: String = "",
    isError: Boolean = false,
    loading: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun buildDayBuckets(points: List<HistoryPoint>): List<DayBucket> =
    points.groupBy { it.timestampMs - (it.timestampMs % DAY_MS) }
        .entries
        .sortedByDescending { it.key }
        .map { (k, v) -> DayBucket(key = k, points = v) }

@OptIn(ExperimentalTime::class)
private fun dayLabel(dayStartMs: Long, nowMs: Long): String {
    val nowStart = nowMs - (nowMs % DAY_MS)
    return when {
        dayStartMs == nowStart -> "Today"
        dayStartMs == nowStart - DAY_MS -> "Yesterday"
        else -> Instant.fromEpochMilliseconds(dayStartMs).toString().substringBefore('T').take(10)
    }
}

private data class DayBucket(
    val key: Long,
    val points: List<HistoryPoint>,
)

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L
