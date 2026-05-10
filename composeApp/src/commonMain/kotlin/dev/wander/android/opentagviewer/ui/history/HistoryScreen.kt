package io.github.tieo.taghistory.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import io.github.tieo.taghistory.ui.util.AlwaysSpinningIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER")
    reverseGeocode: (suspend (Double, Double) -> String?)? = null,
    /**
     * Platform-injected handler for "share this day as GPX". Receives
     * the title (used in the file name + GPX trk name), the day-bucket
     * label ("Today", "2026-05-08", …), and the chronological points
     * making up that day. Implementations should serialise the GPX and
     * fire ACTION_SEND. No-op by default.
     */
    onShareGpx: ((title: String, dayLabel: String, points: List<HistoryPoint>) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        PerfTrace.start("history-open beacon=$title")
        val end = Clock.System.now().toEpochMilliseconds()
        viewModel.load(end - 7L * DAY_MS, end)
    }
    LaunchedEffect(state.points.size) {
        PerfTrace.mark("HistoryScreen recompose points=${state.points.size}")
    }

    val days = remember(state.points) { buildDayBuckets(state.points) }

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
    // Day-scoped entries: rebuild from VM-supplied entries that are
    // already classified + filtered, intersected with the current day.
    val dayEntries = remember(state.entries, selectedDay) {
        if (selectedDay == null) emptyList()
        else state.entries.filter { e ->
            localDayStart(e.timestampMs) == selectedDay.key
        }
    }

    var selectedPointId by remember { mutableStateOf<String?>(null) }
    val selectedPointIdx = remember(chronological, selectedPointId) {
        chronological.indexOfFirst { it.id == selectedPointId }
            .let { if (it >= 0) it else (chronological.size - 1).coerceAtLeast(0) }
    }

    val summary by remember(chronological) {
        derivedStateOf { buildDaySummary(chronological) }
    }

    val themeDefault = defaultBasemap()
    var basemap by remember(themeDefault) { mutableStateOf(themeDefault) }

    var routeVisible by remember { mutableStateOf(true) }

    var lastRenderedCount by remember { mutableIntStateOf(-1) }

    // Sheet peek = 25% of viewport height (down from 35% — the user
    // wanted the sheet not to eat ~10% extra). Top inset = status
    // bar + floating buttons strip; the map is told about both so
    // the camera fit only considers the slice that's actually
    // visible between the notch and the sheet, not the whole window.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val sheetPeek = screenHeightDp * 0.25f
    val sheetPeekPx = with(density) { sheetPeek.toPx().toInt() }
    val statusBarPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Status bar + button height (48 dp) + the 12 dp top + 12 dp bottom
    // padding around the floating row.
    val topInsetDp = statusBarPad + 48.dp + 24.dp
    val topInsetPx = with(density) { topInsetDp.toPx().toInt() }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedPointIdx, chronological.size) {
        if (chronological.isEmpty()) return@LaunchedEffect
        val target = dayEntries.indexOfFirst { e ->
            when (e) {
                is HistoryEntry.Stop -> e.members.any { it.id == chronological.getOrNull(selectedPointIdx)?.id }
                is HistoryEntry.Move -> e.point.id == chronological.getOrNull(selectedPointIdx)?.id
            }
        }
        if (target < 0) return@LaunchedEffect
        // Only scroll when the target row is OUTSIDE the current visible
        // window. Otherwise selecting a row that's already on screen
        // would scroll it to the top and push other already-visible rows
        // off — so tapping history_item_1 used to silently hide
        // history_item_0 even though both are sitting right there.
        val visible = listState.layoutInfo.visibleItemsInfo
        val firstIdx = visible.firstOrNull()?.index ?: -1
        val lastIdx = visible.lastOrNull()?.index ?: -1
        val onScreen = target in firstIdx..lastIdx
        if (!onScreen) listState.scrollToItem(target)
    }

    var showDatePicker by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Custom fixed-height bottom sheet. Replaces M3's
    // BottomSheetScaffold because the scaffold sized its sheet
    // content at the EXPANDED height regardless of state, which
    // meant a 3-item LazyColumn with weight(1f) inside never
    // overflowed its viewport (~full sheet height) at peek; the
    // list was therefore not scrollable. Bounding the sheet to a
    // fixed peek height makes the inner LazyColumn overflow as
    // soon as it has more items than fit, and scroll works.
    Box(modifier = modifier.fillMaxSize()) {
        HistoryMapView(
            points = chronological,
            selectedPointIndex = selectedPointIdx,
            basemap = basemap,
            routeVisible = routeVisible,
            topInsetPx = topInsetPx,
            bottomInsetPx = sheetPeekPx,
            onPointSelected = { id -> selectedPointId = id },
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

        // Fixed-height sheet at bottom.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetPeek),
            color = MaterialTheme.colorScheme.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
            ),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Decorative pill at top — not draggable, but kept for
                // visual continuity with the previous M3 sheet so
                // existing screenshots / docs still look familiar.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                SheetContent(
                    days = days,
                    dayIdx = dayIdx,
                    chronological = chronological,
                    dayEntries = dayEntries,
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
                    onDayTitleTap = { showDatePicker = true },
                    onDayTitleLongPress = {
                        selectedDay?.let { day ->
                            val label = dayLabel(day.key, Clock.System.now().toEpochMilliseconds())
                            onShareGpx?.invoke(title, label, chronological)
                                ?: coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        "GPX export not available on this platform",
                                    )
                                }
                        }
                    },
                    onSelectPoint = { id -> selectedPointId = id },
                    onRefresh = { viewModel.refresh() },
                    onRetry = { viewModel.refresh() },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = sheetPeek + 8.dp),
        )
    }

    if (showDatePicker) {
        HistoryDatePickerDialog(
            availableDayKeys = remember(days) { days.map { it.key }.toSet() },
            selectedKey = selectedDay?.key,
            onPick = { key ->
                selectedDayKey = key
                selectedPointId = null
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)
@Composable
private fun SheetContent(
    days: List<DayBucket>,
    dayIdx: Int,
    chronological: List<HistoryPoint>,
    dayEntries: List<HistoryEntry>,
    selectedPointIdx: Int,
    summary: DaySummary,
    isLoading: Boolean,
    error: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    lastRenderedCount: Int,
    onDayPrev: () -> Unit,
    onDayNext: () -> Unit,
    onDayTitleTap: () -> Unit,
    onDayTitleLongPress: () -> Unit,
    onSelectPoint: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    // See HistoryScreen for the rationale: swallow the residual scroll
    // here so the BottomSheetScaffold never sees it and can't hijack
    // the user's intent to scroll the list.
    val listOwnsScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available
            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = available
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .testTag("map_render_$lastRenderedCount")
                .semantics { contentDescription = "map_render_$lastRenderedCount" },
        )

        // Date row: chevron / day-name (tap = picker, long-press = GPX
        // share) / chevron / refresh. No horizontal swipe gesture: it
        // collided with the bottom-sheet's vertical drag handler and
        // misfired day switches when the user just meant to scroll.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 0.dp),
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onDayTitleTap,
                        onLongClick = onDayTitleLongPress,
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = days.getOrNull(dayIdx)?.key?.let { dayLabel(it, now) } ?: "No data",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = "Pick day",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDayNext,
                enabled = dayIdx > 0,
                modifier = Modifier.testTag("btn_day_next"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
            }
            IconButton(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier.testTag("btn_history_refresh"),
            ) {
                if (isLoading) {
                    AlwaysSpinningIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        if (chronological.isNotEmpty()) {
            DaySummaryStrip(summary = summary, totalPoints = chronological.size)
            HorizontalDivider()
        }

        if (error != null && chronological.isEmpty()) {
            ErrorBlock(message = error, onRetry = onRetry)
            return@Column
        }

        // Plain LazyColumn — no PullToRefreshBox. The previous wrapper
        // intercepted scroll gestures, which combined with the bottom
        // sheet's nested-scroll connection meant the user had to drag
        // the sheet up before the list would scroll.
        EntriesList(
            entries = dayEntries,
            listState = listState,
            selectedPointId = chronological.getOrNull(selectedPointIdx)?.id,
            onSelectPoint = onSelectPoint,
            // weight(1f) gives the LazyColumn a bounded height inside
            // the Column. fillMaxSize alone left the list with the
            // parent's full height, so when it had more rows than fit
            // the peek the viewport extended past the sheet bottom and
            // the LazyColumn never thought it had to scroll. The
            // nestedScroll override still ensures the leftover never
            // bubbles to the BottomSheetScaffold.
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .nestedScroll(listOwnsScroll),
        )
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

@OptIn(ExperimentalTime::class)
@Composable
private fun EntriesList(
    entries: List<HistoryEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedPointId: String?,
    onSelectPoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        itemsIndexed(entries, key = { _, e -> e.id }) { idx, entry ->
            // Stable test-tag tied to list position (used by Maestro
            // tests that scroll the list and assert which row remains
            // visible). Re-introduced after the Stop/Move rewrite
            // accidentally dropped the original `history_item_X` tag
            // which existed on the previous flat HistoryListItem.
            val tag = "history_item_$idx"
            when (entry) {
                is HistoryEntry.Stop -> StopRow(
                    entry = entry,
                    isFirst = idx == 0,
                    isLast = idx == entries.lastIndex,
                    isSelected = entry.members.any { it.id == selectedPointId },
                    testTag = tag,
                    onSelect = { onSelectPoint(entry.anchor.id) },
                    onSelectMember = { p -> onSelectPoint(p.id) },
                )
                is HistoryEntry.Move -> MoveRow(
                    entry = entry,
                    isFirst = idx == 0,
                    isLast = idx == entries.lastIndex,
                    isSelected = entry.point.id == selectedPointId,
                    testTag = tag,
                    onSelect = { onSelectPoint(entry.point.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun StopRow(
    entry: HistoryEntry.Stop,
    isFirst: Boolean,
    isLast: Boolean,
    isSelected: Boolean,
    testTag: String? = null,
    onSelect: () -> Unit,
    onSelectMember: (HistoryPoint) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val nodeColor = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary
    val railColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect() },
            verticalAlignment = Alignment.Top,
        ) {
            // Vertical rail with filled-pin node.
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!isFirst) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(36.dp)
                            .align(Alignment.TopCenter)
                            .background(railColor),
                    )
                }
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(36.dp)
                            .align(Alignment.BottomCenter)
                            .background(railColor),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 24.dp else 20.dp)
                        .clip(CircleShape)
                        .background(nodeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        modifier = Modifier.size(if (isSelected) 16.dp else 14.dp),
                        tint = MaterialTheme.colorScheme.surface,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    entry.anchor.address
                        ?: "%.5f, %.5f".format(entry.anchor.latitude, entry.anchor.longitude),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val arrival = formatLocalTime(entry.arrivalMs)
                val departure = formatLocalTime(entry.departureMs)
                val durationLabel = formatDuration(entry.dwellMs)
                Text(
                    if (entry.dwellMs <= 0L) "At $arrival"
                    else "$arrival → $departure  ·  $durationLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // Expand caret only if there are multiple constituents.
            if (entry.members.size > 1) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess
                                      else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(40.dp))
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            ) {
                entry.members.asReversed().forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMember(p) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "At ${formatLocalTime(p.timestampMs)}  ·  ±${p.horizontalAccuracy} m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun MoveRow(
    entry: HistoryEntry.Move,
    isFirst: Boolean,
    isLast: Boolean,
    isSelected: Boolean,
    testTag: String? = null,
    onSelect: () -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(60.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (!isFirst) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(30.dp)
                        .align(Alignment.TopCenter)
                        .background(railColor),
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(30.dp)
                        .align(Alignment.BottomCenter)
                        .background(railColor),
                )
            }
            Box(
                modifier = Modifier
                    .size(if (isSelected) 18.dp else 14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.TrendingFlat,
                    contentDescription = null,
                    modifier = Modifier.size(if (isSelected) 14.dp else 10.dp),
                    tint = ringColor,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            // Show the distance / duration line only when the VM
            // actually populated both — buildEntries zeros them out
            // for the chronologically-first move of a day so we never
            // show the cross-day gap as if it were a continuous trip.
            // No speed badge: with ~10 min sampling the per-leg km/h
            // is mostly noise.
            if (entry.fromPrevMeters > 0.0 && entry.durationFromPrevMs > 0L) {
                val distLabel = formatDistance(entry.fromPrevMeters)
                val durLabel = formatDuration(entry.durationFromPrevMs)
                Text(
                    "$distLabel · $durLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "At ${formatLocalTime(entry.point.timestampMs)}  ·  ±${entry.point.horizontalAccuracy} m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
private fun HistoryDatePickerDialog(
    availableDayKeys: Set<Long>,
    selectedKey: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // The Material3 date picker emits UTC midnight ms for the chosen
    // date. We store day-bucket keys as local-timezone midnights. To
    // map between them robustly, snap the UTC midnight to local-day
    // start via [localDayStart] after offsetting to noon UTC of the
    // same calendar date — that lands inside the local day's window
    // for any timezone within ±12 h, no kotlinx-datetime needed.
    val noonOffsetMs = 12L * 60L * 60L * 1000L
    fun toLocalDayStart(utcMidnightMs: Long): Long =
        localDayStart(utcMidnightMs + noonOffsetMs)

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedKey,
        selectableDates = remember(availableDayKeys) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    toLocalDayStart(utcTimeMillis) in availableDayKeys
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val utc = pickerState.selectedDateMillis ?: return@TextButton
                    onPick(toLocalDayStart(utc))
                },
            ) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(
            state = pickerState,
            title = {
                Text(
                    "Pick a day",
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            colors = DatePickerDefaults.colors(),
        )
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

private data class DaySummary(
    val distanceMeters: Double,
    val movingMs: Long,
    val stopCount: Int,
)

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
            stops++; inStop = true
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

private fun formatSpeed(kmh: Double): String =
    if (kmh < 1.0) "<1 km/h" else "${kmh.roundToInt()} km/h"

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
