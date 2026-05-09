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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomSheetScaffold
import io.github.tieo.taghistory.ui.util.AlwaysSpinningIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.tieo.taghistory.ui.map.BasemapCycleButton
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.defaultBasemap
import io.github.tieo.taghistory.util.PerfTrace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * History screen for one beacon. Lays the map full-bleed under a
 * Material3 bottom sheet that hosts the per-day list of points,
 * Maps-Timeline-style: vertical rail with colored nodes, stop / move
 * icons, summary header (km · time · stops), date selector, refresh
 * indicator.
 *
 * The ViewModel owns DB access, network fetch, and reverse-geocoding —
 * this composable only holds the UI's "which day / which point is
 * selected, is the route hidden" state.
 */
@OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Deprecated: addresses are now resolved + cached by the ViewModel.
     * Kept on the signature so the existing factory wiring compiles, but
     * unused. Will be removed once all callers stop passing it.
     */
    @Suppress("UNUSED_PARAMETER")
    reverseGeocode: (suspend (Double, Double) -> String?)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        PerfTrace.start("history-open beacon=$title")
        val end = Clock.System.now().toEpochMilliseconds()
        // DB-only path. Reports were already fetched + decrypted by the
        // map screen's periodic refresh and persisted by the sync
        // orchestrator, so opening history shouldn't re-hit Apple every
        // time. The "Retry" button on the error block + a future
        // pull-to-refresh stay as opt-in entrypoints into fetchAndLoad.
        viewModel.load(end - 7L * DAY_MS, end)
    }
    LaunchedEffect(state.points.size) {
        PerfTrace.mark("HistoryScreen recompose points=${state.points.size}")
    }

    val days = remember(state.points) { buildDayBuckets(state.points) }

    // Day + point selection are persisted by stable key (day-start ms,
    // point hash id) so the background refresh re-emitting state.points
    // doesn't snap the user back to today / to the newest point.
    var selectedDayKey by remember { mutableStateOf<Long?>(null) }
    val dayIdx = remember(days, selectedDayKey) {
        if (selectedDayKey == null) 0
        else days.indexOfFirst { it.key == selectedDayKey }.let {
            if (it >= 0) it else 0
        }
    }
    val selectedDay = days.getOrNull(dayIdx)

    val chronological = remember(selectedDay) {
        selectedDay?.points?.sortedBy { it.timestampMs } ?: emptyList()
    }

    var selectedPointId by remember { mutableStateOf<String?>(null) }
    val selectedPointIdx = remember(chronological, selectedPointId) {
        chronological.indexOfFirst { it.id == selectedPointId }
            .let { if (it >= 0) it else (chronological.size - 1).coerceAtLeast(0) }
    }

    // Daily summary — derived once per chronological list.
    val summary by remember(chronological) {
        derivedStateOf { buildDaySummary(chronological) }
    }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    val themeDefault = defaultBasemap()
    var basemap by remember(themeDefault) { mutableStateOf(themeDefault) }

    // Maps-style: eye-toggle in the corner hides the polyline. The
    // dots stay visible so the user can still pick stops out of the map.
    var routeVisible by remember { mutableStateOf(true) }

    var lastRenderedCount by remember { mutableIntStateOf(-1) }

    // Maps-style geometry: sheet peek = 35% of viewport height. The
    // remaining ~65% is the visible map slice used to fit the day's
    // bounds; without this, points underneath the sheet got cut off.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val sheetPeek = screenHeightDp * 0.35f
    val sheetPeekPx = with(density) { sheetPeek.toPx().toInt() }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedPointIdx, chronological.size) {
        if (chronological.isNotEmpty()) {
            val listIdx = (chronological.size - 1 - selectedPointIdx)
                .coerceIn(0, (chronological.size - 1).coerceAtLeast(0))
            listState.scrollToItem(listIdx)
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = modifier.fillMaxSize(),
        // Maps Timeline gives the sheet ~35% of the viewport at peek;
        // the rest is the map. Computed from the actual screen height
        // so it scales for tablets and landscape.
        sheetPeekHeight = sheetPeek,
        // Custom drag handle: the M3 default leaves a chunky vertical
        // gap below the pill before the first row of content. The slot
        // overrides that with a single thin pill in a small Box.
        sheetDragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BottomSheetDefaults.DragHandle()
            }
        },
        sheetContent = {
            SheetContent(
                days = days,
                dayIdx = dayIdx,
                chronological = chronological,
                selectedPointIdx = selectedPointIdx,
                summary = summary,
                isLoading = state.isLoading,
                error = state.error,
                listState = listState,
                lastRenderedCount = lastRenderedCount,
                onDayPrev = {
                    if (dayIdx < days.size - 1) {
                        selectedDayKey = days[dayIdx + 1].key
                        selectedPointId = null
                    }
                },
                onDayNext = {
                    if (dayIdx > 0) {
                        selectedDayKey = days[dayIdx - 1].key
                        selectedPointId = null
                    }
                },
                onSelectPoint = { idx ->
                    chronological.getOrNull(idx)?.let { selectedPointId = it.id }
                },
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
                routeVisible = routeVisible,
                bottomInsetPx = sheetPeekPx,
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

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledIconButton(
                    onClick = { routeVisible = !routeVisible },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.testTag("btn_route_visibility"),
                ) {
                    Icon(
                        imageVector = if (routeVisible) Icons.Filled.Visibility
                                      else Icons.Filled.VisibilityOff,
                        contentDescription = if (routeVisible) "Hide route" else "Show route",
                    )
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
                )
            }
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
    summary: DaySummary,
    isLoading: Boolean,
    error: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
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

        if (chronological.isNotEmpty()) {
            DaySummaryStrip(summary = summary, totalPoints = chronological.size)
            HorizontalDivider()
        }

        if (isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                AlwaysSpinningIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Refreshing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            itemsIndexed(
                reversed,
                key = { _, p -> p.id },
            ) { listIdx, point ->
                val chronoIdx = chronological.size - 1 - listIdx
                val isSelected = chronoIdx == selectedPointIdx
                val isFirst = listIdx == 0
                val isLast = listIdx == reversed.lastIndex
                HistoryListItem(
                    point = point,
                    isSelected = isSelected,
                    isFirstInList = isFirst,
                    isLastInList = isLast,
                    testTag = "history_item_$listIdx",
                    onClick = { onSelectPoint(chronoIdx) },
                )
            }
        }
    }
}

/** Maps-style: distance · time on the move · stop count, all on one row. */
@Composable
private fun DaySummaryStrip(summary: DaySummary, totalPoints: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryStat(
            label = formatDistance(summary.distanceMeters),
            sub = "distance",
        )
        SummaryStat(
            label = formatDuration(summary.movingMs),
            sub = "moving",
        )
        SummaryStat(
            label = "${summary.stopCount}",
            sub = if (summary.stopCount == 1) "stop" else "stops",
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Timeline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "$totalPoints",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, sub: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryListItem(
    point: HistoryPoint,
    isSelected: Boolean,
    isFirstInList: Boolean,
    isLastInList: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val nodeColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        point.kind == HistoryPointKind.STOP -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        // Vertical rail column. The line is drawn as two thin Boxes so
        // we can hide the segment above the first row and below the
        // last, giving the list a clean cap on each end like Maps does.
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(60.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Top segment.
            if (!isFirstInList) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(30.dp)
                        .align(Alignment.TopCenter)
                        .background(railColor),
                )
            }
            // Bottom segment.
            if (!isLastInList) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(30.dp)
                        .align(Alignment.BottomCenter)
                        .background(railColor),
                )
            }
            // Node icon. STOPs get a filled pause-style circle; MOVEs
            // get a smaller arrow inside an outlined ring.
            Box(
                modifier = Modifier
                    .size(if (isSelected) 22.dp else 16.dp)
                    .clip(CircleShape)
                    .background(if (point.kind == HistoryPointKind.STOP) nodeColor else Color.Transparent)
                    .then(
                        if (point.kind == HistoryPointKind.MOVE) Modifier
                            .background(MaterialTheme.colorScheme.surface)
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (point.kind) {
                        HistoryPointKind.STOP -> Icons.Filled.Place
                        HistoryPointKind.MOVE -> Icons.AutoMirrored.Filled.TrendingFlat
                    },
                    contentDescription = null,
                    modifier = Modifier.size(if (isSelected) 14.dp else 12.dp),
                    tint = if (point.kind == HistoryPointKind.STOP)
                        MaterialTheme.colorScheme.surface
                    else nodeColor,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text(
                point.address ?: "%.5f, %.5f".format(point.latitude, point.longitude),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (point.kind == HistoryPointKind.STOP)
                    FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
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
            AlwaysSpinningIndicator(modifier = Modifier.size(48.dp))
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

private data class DaySummary(
    val distanceMeters: Double,
    val movingMs: Long,
    val stopCount: Int,
)

/**
 * Walks the day's points (chronological) summing pairwise distance and
 * counting transitions in/out of STOP runs. Distance is in meters
 * (Haversine); moving time is the wall-clock time between consecutive
 * MOVE points; stops are contiguous runs of STOP-classified points.
 */
private fun buildDaySummary(points: List<HistoryPoint>): DaySummary {
    if (points.size < 2) {
        return DaySummary(
            distanceMeters = 0.0,
            movingMs = 0L,
            stopCount = points.count { it.kind == HistoryPointKind.STOP }.let {
                if (it > 0) 1 else 0
            },
        )
    }
    var distance = 0.0
    var movingMs = 0L
    var stops = 0
    var inStop = false
    for (i in points.indices) {
        val p = points[i]
        if (p.kind == HistoryPointKind.STOP && !inStop) {
            stops++
            inStop = true
        } else if (p.kind != HistoryPointKind.STOP) {
            inStop = false
        }
        if (i == 0) continue
        val prev = points[i - 1]
        distance += haversineMeters(
            prev.latitude, prev.longitude,
            p.latitude, p.longitude,
        )
        if (prev.kind != HistoryPointKind.STOP || p.kind != HistoryPointKind.STOP) {
            movingMs += (p.timestampMs - prev.timestampMs).coerceAtLeast(0L)
        }
    }
    return DaySummary(distance, movingMs, stops)
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2).let { it * it } +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
        sin(dLon / 2).let { it * it }
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

private fun formatDistance(meters: Double): String =
    if (meters < 1_000.0) "${meters.roundToInt()} m"
    else "%.1f km".format(meters / 1_000.0)

private fun formatDuration(ms: Long): String {
    if (ms < 60_000L) return "${(ms / 1_000L).coerceAtLeast(0)} s"
    val minutes = (ms / 60_000L)
    if (minutes < 60L) return "${minutes} min"
    val hours = minutes / 60L
    val remMin = minutes % 60L
    return if (remMin == 0L) "${hours} h" else "${hours} h ${remMin} min"
}

private fun buildDayBuckets(points: List<HistoryPoint>): List<DayBucket> =
    points.groupBy { localDayStart(it.timestampMs) }
        .entries
        .sortedByDescending { it.key }
        .map { (k, v) -> DayBucket(key = k, points = v) }

@OptIn(ExperimentalTime::class)
private fun dayLabel(dayStartMs: Long, nowMs: Long): String {
    val nowStart = localDayStart(nowMs)
    return when {
        dayStartMs == nowStart -> "Today"
        dayStartMs == nowStart - DAY_MS -> "Yesterday"
        else -> formatLocalDate(dayStartMs)
    }
}

private data class DayBucket(
    val key: Long,
    val points: List<HistoryPoint>,
)

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L
