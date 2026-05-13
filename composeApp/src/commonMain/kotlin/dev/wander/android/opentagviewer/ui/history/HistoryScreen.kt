package io.github.tieo.taghistory.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timeline
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
import org.jetbrains.compose.resources.stringResource
import taghistory.composeapp.generated.resources.Res
import taghistory.composeapp.generated.resources.*

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
    /**
     * Platform "navigate to coordinates" handler. Same lambda the
     * map screen uses for the card's Route action — typically fires
     * a `geo:lat,lon?q=lat,lon(label)` chooser. Default is a no-op
     * so the History route button greys out on hosts that don't
     * wire one through.
     */
    onRoute: (lat: Double, lon: Double, label: String) -> Unit = noopRoute,
    /**
     * Every known beacon (id + display name + optional emoji), used
     * to populate the device switcher dialog when the user taps the
     * title chip. Empty list disables the switcher (renders a static
     * label instead of a clickable chip).
     */
    beacons: List<HistoryBeaconChoice> = emptyList(),
    /**
     * Host callback to swap the currently viewed beacon. Implementations
     * typically pop+push a new History screen so the ViewModel rebinds
     * via remember(beaconId).
     */
    onSwitchBeacon: (beaconId: String, title: String) -> Unit = { _, _ -> },
) {
    val currentBeacon = remember(beacons, title) {
        beacons.firstOrNull { it.displayName == title }
    }
    val canSwitch = beacons.size > 1
    var showSwitcher by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on viewModel so switching beacon (which gives us a new VM
    // via remember(beaconId)) re-fires load() against the new VM.
    // Previously keyed on Unit, which only triggered once for the
    // composable's lifetime — the switcher swapped in a new VM but
    // nobody ever called load() on it, hence "No data".
    LaunchedEffect(viewModel) {
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
    // Hide the per-row city when every entry in the day resolved to
    // the same city — the day header already establishes location.
    // If cities differ within a day, each row shows its own city on
    // a second line (not comma-appended to the street).
    val hideCity = remember(dayEntries) { commonCityOrNull(dayEntries) != null }

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

    var lastRenderedCount by remember { mutableIntStateOf(-1) }

    // Sheet height = 40% of viewport at default. Map gets the
    // remaining 60%. Top inset = status bar + floating buttons strip;
    // the map is told about both so the camera fit only considers
    // the slice that's actually visible between the notch and the
    // sheet, not the whole window.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val sheetPeek = screenHeightDp * 0.40f
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
    val todayStr = stringResource(Res.string.history_today)
    val yesterdayStr = stringResource(Res.string.history_yesterday)
    val gpxUnavailableStr = stringResource(Res.string.history_gpx_unavailable)

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
            routeVisible = true,
            topInsetPx = topInsetPx,
            bottomInsetPx = sheetPeekPx,
            onPointSelected = { id -> selectedPointId = id },
            onRendered = { lastRenderedCount = it.size },
            modifier = Modifier.fillMaxSize(),
        )

        // Single top row so back / title chip / route+basemap don't
        // overlap. Each "slot" is one logical group; the chip takes
        // the leftover middle space (centered) and shrinks via
        // Modifier.weight so long names don't shove the side buttons
        // off-screen. Same statusBarsPadding + top padding applies to
        // the whole row so all three groups share the same baseline.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = onBack,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }

            // Title chip — shows current device (emoji + name).
            // Tappable when more than one beacon is known, opens a
            // picker. weight(1f, fill = false) lets the chip shrink
            // when the side buttons need room but never grow past its
            // intrinsic width, so it stays centered between them.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .then(
                            if (canSwitch) Modifier.clickable { showSwitcher = true }
                            else Modifier,
                        )
                        .testTag("history_title_chip"),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val emoji = currentBeacon?.emoji
                        if (!emoji.isNullOrBlank()) {
                            Text(emoji, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                        if (canSwitch) {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = stringResource(Res.string.history_switch_device_cd),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            val selectedPoint = chronological.getOrNull(selectedPointIdx)
            FilledIconButton(
                onClick = {
                    selectedPoint?.let { p ->
                        onRoute(p.latitude, p.longitude, title)
                    }
                },
                enabled = selectedPoint != null && onRoute != noopRoute,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.testTag("btn_route_to_selected"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Directions,
                    contentDescription = stringResource(Res.string.history_route_to_selected_cd),
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
                // No drag pill — sheet is a fixed division, not a
                // draggable surface. A tiny top spacer keeps the date
                // row clear of the rounded-corner curve.
                Spacer(Modifier.height(8.dp))
                SheetContent(
                    days = days,
                    dayIdx = dayIdx,
                    chronological = chronological,
                    dayEntries = dayEntries,
                    selectedPointIdx = selectedPointIdx,
                    summary = summary,
                    isLoading = state.isLoading,
                    error = state.error,
                    hideCity = hideCity,
                    todayStr = todayStr,
                    yesterdayStr = yesterdayStr,
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
                            val label = dayLabel(
                                day.key,
                                Clock.System.now().toEpochMilliseconds(),
                                todayStr,
                                yesterdayStr,
                            )
                            onShareGpx?.invoke(title, label, chronological)
                                ?: coroutineScope.launch {
                                    snackbarHostState.showSnackbar(gpxUnavailableStr)
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

    if (showSwitcher) {
        DeviceSwitcherDialog(
            beacons = beacons,
            currentTitle = title,
            onPick = { choice ->
                showSwitcher = false
                if (choice.displayName != title) {
                    onSwitchBeacon(choice.beaconId, choice.displayName)
                }
            },
            onDismiss = { showSwitcher = false },
        )
    }
}

@Composable
private fun DeviceSwitcherDialog(
    beacons: List<HistoryBeaconChoice>,
    currentTitle: String,
    onPick: (HistoryBeaconChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
        title = { Text(stringResource(Res.string.history_switch_device)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(beacons, key = { _, b -> b.beaconId }) { _, b ->
                    val isCurrent = b.displayName == currentTitle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(b) }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .testTag("history_switch_${b.beaconId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!b.emoji.isNullOrBlank()) {
                            Text(b.emoji, style = MaterialTheme.typography.titleMedium)
                        } else {
                            Spacer(Modifier.width(20.dp))
                        }
                        Text(
                            b.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Text(
                                stringResource(Res.string.history_viewing),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    )
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
    hideCity: Boolean,
    todayStr: String,
    yesterdayStr: String,
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.history_previous_day_cd))
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
                    text = days.getOrNull(dayIdx)?.key?.let {
                        dayLabel(it, now, todayStr, yesterdayStr)
                    } ?: stringResource(Res.string.history_no_data),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = stringResource(Res.string.history_pick_day_cd),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDayNext,
                enabled = dayIdx > 0,
                modifier = Modifier.testTag("btn_day_next"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(Res.string.history_next_day_cd))
            }
            // Refresh button removed. The view now subscribes to the
            // LocationReport table via SQLDelight's Flow, so any new
            // fix landing in the DB (from the map screen's periodic
            // refresh, the background worker, the manual refresh-now
            // button in Settings) shows up in this list automatically
            // — no manual reload needed.
            if (isLoading) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    AlwaysSpinningIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
            hideCity = hideCity,
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
            sub = stringResource(Res.string.history_distance),
        )
        SummaryStat(
            label = formatDuration(summary.movingMs),
            sub = stringResource(Res.string.history_moving),
        )
        SummaryStat(
            label = "${summary.stopCount}",
            sub = if (summary.stopCount == 1) stringResource(Res.string.history_stop) else stringResource(Res.string.history_stops),
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
    hideCity: Boolean,
    onSelectPoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Interleave entries with leg-label rows. The leg shows distance +
    // duration between adjacent entries (older -> newer) ON the rail
    // itself, not as a subline on the row above. Below-threshold legs
    // (jitter) collapse to nothing so the rail just runs straight
    // through.
    val rendered = remember(entries) { buildRenderedItems(entries) }
    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        itemsIndexed(rendered, key = { _, item -> item.key }) { _, item ->
            when (item) {
                is RenderedItem.EntryItem -> {
                    val tag = "history_item_${item.idx}"
                    when (val entry = item.entry) {
                        is HistoryEntry.Stop -> StopRow(
                            entry = entry,
                            isFirst = item.isFirst,
                            isLast = item.isLast,
                            isSelected = entry.members.any { it.id == selectedPointId },
                            hideCity = hideCity,
                            testTag = tag,
                            onSelect = { onSelectPoint(entry.anchor.id) },
                            onSelectMember = { p -> onSelectPoint(p.id) },
                        )
                        is HistoryEntry.Move -> MoveRow(
                            entry = entry,
                            isFirst = item.isFirst,
                            isLast = item.isLast,
                            isSelected = entry.point.id == selectedPointId,
                            hideCity = hideCity,
                            testTag = tag,
                            onSelect = { onSelectPoint(entry.point.id) },
                        )
                    }
                }
                is RenderedItem.LegItem -> LegLabel(
                    distanceMeters = item.distanceMeters,
                    durationMs = item.durationMs,
                )
            }
        }
    }
}

/**
 * Walks the (newest-first) entry list, emitting an [EntryItem] for
 * each row and inserting a [LegItem] between any two consecutive
 * entries whose travel exceeds the jitter floor. Same-day check
 * prevents an overnight gap from rendering as a long leg.
 */
private fun buildRenderedItems(entries: List<HistoryEntry>): List<RenderedItem> {
    if (entries.isEmpty()) return emptyList()
    val out = mutableListOf<RenderedItem>()
    entries.forEachIndexed { i, e ->
        out += RenderedItem.EntryItem(
            entry = e,
            idx = i,
            isFirst = i == 0,
            isLast = i == entries.lastIndex,
        )
        if (i < entries.lastIndex) {
            val older = entries[i + 1]
            val newerAnchor = entryAnchor(e)
            val olderAnchor = entryAnchor(older)
            val sameDay = localDayStart(newerAnchor.timestampMs) ==
                localDayStart(olderAnchor.timestampMs)
            if (sameDay) {
                val dist = haversineMeters(
                    newerAnchor.latitude, newerAnchor.longitude,
                    olderAnchor.latitude, olderAnchor.longitude,
                )
                val dur = (newerAnchor.timestampMs - olderAnchor.timestampMs)
                    .coerceAtLeast(0L)
                val accFloor = maxOf(
                    newerAnchor.horizontalAccuracy,
                    olderAnchor.horizontalAccuracy,
                )
                if (isRealMove(dist, dur, accFloor)) {
                    out += RenderedItem.LegItem(
                        distanceMeters = dist,
                        durationMs = dur,
                        key = "leg-${e.id}-${older.id}",
                    )
                }
            }
        }
    }
    return out
}

private fun entryAnchor(e: HistoryEntry): HistoryPoint = when (e) {
    is HistoryEntry.Stop -> e.anchor
    is HistoryEntry.Move -> e.point
}

private sealed class RenderedItem {
    abstract val key: String

    data class EntryItem(
        val entry: HistoryEntry,
        val idx: Int,
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : RenderedItem() {
        override val key: String get() = entry.id
    }

    data class LegItem(
        val distanceMeters: Double,
        val durationMs: Long,
        override val key: String,
    ) : RenderedItem()
}

@Composable
private fun LegLabel(distanceMeters: Double, durationMs: Long) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    // Box layered: full-height rail bar at x = 17 dp (matches the
    // 36 dp rail column the entry rows use) + chip aligned to the
    // rail's right edge so it reads "1.5 km · 7 h 9 min" from the
    // rail outward instead of being centered on the 36 dp column
    // (which pushed the distance prefix off-screen on the left).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 17.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(railColor),
        )
        Surface(
            modifier = Modifier
                .padding(start = 18.dp)
                .align(Alignment.CenterStart),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 0.dp,
        ) {
            Text(
                text = "${formatDistance(distanceMeters)} · ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
    hideCity: Boolean,
    testTag: String? = null,
    onSelect: () -> Unit,
    onSelectMember: (HistoryPoint) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val nodeColor = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary
    val parsed = entry.anchor.address?.let { parseAddress(it) }
        ?: ParsedAddress(
            "%.5f, %.5f".format(entry.anchor.latitude, entry.anchor.longitude),
            null,
        )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        TimelineEntryRow(
            time = formatLocalTime(entry.anchor.timestampMs),
            street = parsed.street,
            city = if (hideCity) null else parsed.city,
            subline = run {
                val arrival = formatLocalTime(entry.arrivalMs)
                val departure = formatLocalTime(entry.departureMs)
                if (entry.dwellMs > 0L)
                    "$arrival → $departure  ·  ${formatDuration(entry.dwellMs)}"
                else
                    "±${entry.anchor.horizontalAccuracy} m"
            },
            isFirst = isFirst,
            isLast = isLast,
            isSelected = isSelected,
            isStop = true,
            nodeColor = nodeColor,
            trailing = if (entry.members.size > 1) {
                {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess
                                          else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) stringResource(Res.string.history_collapse_cd) else stringResource(Res.string.history_expand_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else null,
            onClick = onSelect,
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 124.dp, end = 16.dp, bottom = 8.dp),
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
                            "${formatLocalTime(p.timestampMs)}  ·  ±${p.horizontalAccuracy} m",
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
    hideCity: Boolean,
    testTag: String? = null,
    onSelect: () -> Unit,
) {
    val nodeColor = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
    // Distance + duration to the prior entry now live on the rail
    // between rows (see LegLabel), not as a subline here. Keep the
    // accuracy chip — it's per-row info that doesn't fit on the rail.
    val subline = "±${entry.point.horizontalAccuracy} m"
    val parsed = entry.point.address?.let { parseAddress(it) }
        ?: ParsedAddress(
            "%.5f, %.5f".format(entry.point.latitude, entry.point.longitude),
            null,
        )
    TimelineEntryRow(
        time = formatLocalTime(entry.point.timestampMs),
        street = parsed.street,
        city = if (hideCity) null else parsed.city,
        subline = subline,
        isFirst = isFirst,
        isLast = isLast,
        isSelected = isSelected,
        isStop = false,
        nodeColor = nodeColor,
        trailing = null,
        onClick = onSelect,
        testTag = testTag,
    )
}

/**
 * Shared row layout for both Stop and Move entries:
 *
 *   [ rail (full-height bar with node) | time (big, fixed width) | address column | trailing ]
 *
 * The rail uses `Modifier.height(IntrinsicSize.Max)` on the parent Row so
 * its `fillMaxHeight()` matches the actual rendered row height — which
 * means consecutive rows' rails always touch even if the content height
 * varies between Stop (taller, two text lines) and Move (shorter).
 */
@Composable
private fun TimelineEntryRow(
    time: String,
    street: String,
    city: String?,
    subline: String,
    isFirst: Boolean,
    isLast: Boolean,
    isSelected: Boolean,
    isStop: Boolean,
    nodeColor: androidx.compose.ui.graphics.Color,
    trailing: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable { onClick() }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Rail column: full-height bar, half-height covers on first/last
        // so the rail visually starts/ends at the row's center instead
        // of running off-screen.
        Box(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(railColor),
            )
            if (isFirst) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(0.5f)
                        .align(Alignment.TopCenter)
                        .background(surfaceColor),
                )
            }
            if (isLast) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(0.5f)
                        .align(Alignment.BottomCenter)
                        .background(surfaceColor),
                )
            }
            // Node — filled circle for stops, hollow ring for moves.
            // Slightly larger when selected so the user can see which
            // map dot the list row corresponds to at a glance.
            val nodeSize = when {
                isSelected -> 22.dp
                isStop -> 18.dp
                else -> 14.dp
            }
            // No pin icon — that glyph reads as "you are here" /
            // current location, which a year-old stop fix isn't.
            // Just the filled circle for stops, hollow ring for
            // moves. Differentiation is shape alone, no glyph.
            Box(
                modifier = Modifier
                    .size(nodeSize)
                    .clip(CircleShape)
                    .background(if (isStop) nodeColor else surfaceColor)
                    .border(
                        width = if (isStop) 0.dp else 2.dp,
                        color = if (isStop) androidx.compose.ui.graphics.Color.Transparent
                                else nodeColor,
                        shape = CircleShape,
                    )
                    .align(Alignment.Center),
            )
        }
        // Time — bigger, fixed-width column on the left of the info.
        Text(
            text = time,
            modifier = Modifier
                .width(76.dp)
                .padding(start = 4.dp, end = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
        )
        // Address + optional city line + subline.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                street,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isStop) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (city != null) {
                Text(
                    city,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(
                subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) trailing()
        else Spacer(Modifier.width(8.dp))
    }
}

private data class ParsedAddress(val street: String, val city: String?)

/**
 * Split a geocoded address into street + city portions, always
 * dropping the trailing country segment. Geocoder.getAddressLine(0)
 * is typically `"Tulpenweg 44, 89584 Ehingen, Germany"`; we split on
 * commas, drop the last segment as country, and treat the rest as
 * street (first) + city (middle). The history list then either hides
 * the city (if a whole day is in one city) or renders it on a second
 * line beneath the street — never comma-appended, since that read
 * like a single overlong address line.
 */
private fun parseAddress(full: String): ParsedAddress {
    val parts = full.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ParsedAddress(full, null)
    val street = parts[0]
    if (parts.size <= 1) return ParsedAddress(street, null)
    val cityParts = parts.drop(1).dropLast(1)
    return ParsedAddress(street, cityParts.joinToString(", ").ifBlank { null })
}

/**
 * If every entry in this day's address list resolves to the same
 * city, return that city — the history header already shows the day
 * so repeating "Ehingen" on every row is noise. If addresses span
 * multiple cities (or none have been geocoded yet) returns null and
 * the row renderer falls back to showing the city on each line.
 */
private fun commonCityOrNull(entries: List<HistoryEntry>): String? {
    val cities = entries.mapNotNull { e ->
        val raw = when (e) {
            is HistoryEntry.Stop -> e.anchor.address
            is HistoryEntry.Move -> e.point.address
        }
        raw?.let { parseAddress(it).city }
    }
    if (cities.isEmpty()) return null
    val first = cities.first()
    return if (cities.all { it == first }) first else null
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
                Text(stringResource(Res.string.open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    ) {
        DatePicker(
            state = pickerState,
            title = {
                Text(
                    stringResource(Res.string.history_pick_a_day),
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
            Text(stringResource(Res.string.retry))
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
        val d = haversineMeters(prev.latitude, prev.longitude, p.latitude, p.longitude)
        val dt = (p.timestampMs - prev.timestampMs).coerceAtLeast(0L)
        val accFloor = maxOf(prev.horizontalAccuracy, p.horizontalAccuracy)
        if (isRealMove(d, dt, accFloor)) {
            distance += d
            // The fix-to-fix gap dt is mostly the tag SITTING at the
            // previous location — only the tail end was actual travel.
            // Cap each leg's "moving time" contribution at the time
            // it would take to walk the distance (5 km/h ≈ 1.4 m/s).
            // Without this, a 200 m hop after 4 h of sitting still
            // credited the full 4 h as "moving time".
            val walkMs = (d / 1.4 * 1000.0).toLong()
            movingMs += minOf(dt, walkMs)
        }
    }
    return DaySummary(distance, movingMs, stops)
}

/** Anything smaller than this is below the "interesting motion" UX floor. */
private const val MIN_MOVE_METERS = 20.0

/**
 * Borderline leg (dist between MULT_FLOOR and MULT_TRUSTED times the
 * accuracy) must clear MIN_SUSTAINED_KMH average speed.
 */
private const val MIN_MOVE_ACCURACY_MULT_FLOOR = 2.0

/** Above this ratio, the leg is clearly larger than GPS noise — trust it regardless of duration. */
private const val MIN_MOVE_ACCURACY_MULT_TRUSTED = 5.0

/** Hard cap. Above this, the fix is teleporting and we treat it as a bad sample. */
private const val MAX_PLAUSIBLE_KMH = 250.0

/** Borderline-leg average-speed floor. Below this, treat as stationary drift over a long window. */
private const val MIN_SUSTAINED_KMH = 1.5

/**
 * Single source of truth for "is this leg real movement, not GPS
 * jitter?" — applied to the day summary, the MoveRow accuracy
 * subline and the rail leg-label so all three agree.
 *
 * Decision tree:
 *  1. distance < 20 m -> jitter (UX floor).
 *  2. distance < 2 * max(accuracy) -> jitter (well within noise radius).
 *  3. speed > 250 km/h -> jitter (teleport).
 *  4. distance >= 5 * max(accuracy) -> real (clearly larger than noise,
 *     trust regardless of duration — handles "tag was stationary for
 *     a long time, then moved 30 m right before this report").
 *  5. otherwise borderline -> real only if average speed is at least
 *     a slow walk (1.5 km/h). Filters out a 30 m drift accumulated
 *     across two hours by a tag that never actually went anywhere.
 */
private fun isRealMove(
    distanceMeters: Double,
    durationMs: Long,
    accuracyFloorMeters: Long,
): Boolean {
    if (distanceMeters < MIN_MOVE_METERS) return false
    if (distanceMeters < accuracyFloorMeters * MIN_MOVE_ACCURACY_MULT_FLOOR) return false
    val speedKmh = if (durationMs > 0)
        (distanceMeters / 1000.0) / (durationMs / 3_600_000.0)
    else Double.POSITIVE_INFINITY
    if (speedKmh > MAX_PLAUSIBLE_KMH) return false
    if (distanceMeters >= accuracyFloorMeters * MIN_MOVE_ACCURACY_MULT_TRUSTED) return true
    return speedKmh >= MIN_SUSTAINED_KMH
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
private fun dayLabel(dayStartMs: Long, nowMs: Long, todayStr: String, yesterdayStr: String): String {
    val nowStart = localDayStart(nowMs)
    return when {
        dayStartMs == nowStart -> todayStr
        dayStartMs == nowStart - DAY_MS -> yesterdayStr
        else -> formatLocalDate(dayStartMs)
    }
}

private data class DayBucket(
    val key: Long,
    val points: List<HistoryPoint>,
)

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/** Sentinel for HistoryScreen's onRoute default. Compared by identity. */
private val noopRoute: (Double, Double, String) -> Unit = { _, _, _ -> }

/** One row in the device-switcher dialog. */
data class HistoryBeaconChoice(
    val beaconId: String,
    val displayName: String,
    val emoji: String?,
)
