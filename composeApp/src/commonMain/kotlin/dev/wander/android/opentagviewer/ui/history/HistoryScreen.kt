package io.github.tieo.taghistory.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
        viewModel.fetchAndLoad(end - 7L * DAY_MS, end)
    }

    val days = remember(state.points) { buildDayBuckets(state.points) }

    // dayIdx 0 = most recent day; navigate right = go further back in time.
    var dayIdx by remember(days) { mutableIntStateOf(0) }
    val selectedDay = days.getOrNull(dayIdx)

    // Sorted oldest→newest for map polyline; list shows newest-first.
    val chronological = remember(selectedDay) {
        selectedDay?.points?.sortedBy { it.timestampMs } ?: emptyList()
    }

    // Which point is highlighted on the map. Index into chronological (0 = oldest).
    var selectedPointIdx by remember(chronological) {
        mutableIntStateOf((chronological.size - 1).coerceAtLeast(0))
    }

    // Geocode cache: timestampMs → address string. Populated lazily per point.
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

    // Scroll list to selected item when map selection changes.
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
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
        sheetPeekHeight = 88.dp,
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
                onDayPrev = { if (dayIdx < days.size - 1) dayIdx++ },
                onDayNext = { if (dayIdx > 0) dayIdx-- },
                onSelectPoint = { selectedPointIdx = it },
                onFetchOlder = {
                    val oldest = days.minOfOrNull { it.key }
                        ?: Clock.System.now().toEpochMilliseconds()
                    viewModel.fetchAndLoad(oldest - 7L * DAY_MS, oldest)
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            when {
                state.isLoading && state.points.isEmpty() -> FullScreenMessage(loading = true)
                state.error != null && state.points.isEmpty() ->
                    FullScreenMessage(message = state.error ?: "Couldn't load history", isError = true)
                days.isEmpty() && !state.isLoading -> FullScreenMessage(message = "No history yet")
                chronological.isEmpty() -> FullScreenMessage(message = "No points on this day")
                else -> HistoryMapView(
                    points = chronological,
                    selectedPointIndex = selectedPointIdx,
                    modifier = Modifier.fillMaxSize(),
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
    isLoading: Boolean,
    error: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    addressCache: Map<Long, String>,
    onDayPrev: () -> Unit,
    onDayNext: () -> Unit,
    onSelectPoint: (Int) -> Unit,
    onFetchOlder: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Day navigation row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
                style = MaterialTheme.typography.titleMedium,
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

        // Points summary
        if (chronological.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

        // Loading indicator
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Location list: newest first
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
                    isLast = listIdx == reversed.size - 1,
                    testTag = "history_item_$listIdx",
                    onClick = { onSelectPoint(chronoIdx) },
                )
            }

            item(key = "fetch_older") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(
                        onClick = onFetchOlder,
                        enabled = !isLoading,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.testTag("btn_fetch_older"),
                    ) {
                        Text("Fetch older")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryListItem(
    point: HistoryPoint,
    address: String?,
    isSelected: Boolean,
    isLast: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                address ?: "%.5f, %.5f".format(point.latitude, point.longitude),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
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
    if (!isLast) HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
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
