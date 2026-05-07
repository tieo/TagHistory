package io.github.tieo.taghistory.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tieo.taghistory.ui.nav.PushedScreenScaffold
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val end = Clock.System.now().toEpochMilliseconds()
        viewModel.fetchAndLoad(end - 7L * DAY_MS, end)
    }

    val days = remember(state.points) { buildDayBuckets(state.points) }
    var selectedDayKey by remember { mutableStateOf<Long?>(null) }
    val effectiveSelection = selectedDayKey ?: days.firstOrNull()?.key
    val selectedDay = days.firstOrNull { it.key == effectiveSelection }

    // Sorted oldest→newest so scrubber index 0 = oldest, last = most recent.
    val chronological = remember(selectedDay) {
        selectedDay?.points?.sortedBy { it.timestampMs } ?: emptyList()
    }

    // Reset to most-recent point whenever the day's point list changes.
    var selectedIdx by remember(chronological) {
        mutableStateOf((chronological.size - 1).coerceAtLeast(0))
    }

    PushedScreenScaffold(title = title, onBack = onBack, modifier = modifier) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {

            when {
                state.isLoading && state.points.isEmpty() -> FullScreenMessage(loading = true)
                state.error != null && state.points.isEmpty() ->
                    FullScreenMessage(message = state.error ?: "Couldn't load history", isError = true)
                days.isEmpty() && !state.isLoading -> FullScreenMessage(message = "No history yet")
                else -> {
                    // Map fills entire content area.
                    if (chronological.isNotEmpty()) {
                        HistoryMapView(
                            points = chronological,
                            selectedPointIndex = selectedIdx,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        FullScreenMessage(message = "No points on this day")
                    }

                    // Day selector floats at top.
                    if (days.isNotEmpty()) {
                        DaySelector(
                            days = days,
                            selectedKey = effectiveSelection,
                            onSelect = { selectedDayKey = it },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                                .padding(vertical = 2.dp),
                        )
                    }

                    // Timeline scrubber card floats at bottom.
                    TimelineScrubberCard(
                        points = chronological,
                        selectedIdx = selectedIdx,
                        isLoading = state.isLoading,
                        onSelectIdx = { selectedIdx = it },
                        onFetchOlder = {
                            val oldest = days.minOfOrNull { it.key }
                                ?: Clock.System.now().toEpochMilliseconds()
                            viewModel.fetchAndLoad(oldest - 7L * DAY_MS, oldest)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineScrubberCard(
    points: List<HistoryPoint>,
    selectedIdx: Int,
    isLoading: Boolean,
    onSelectIdx: (Int) -> Unit,
    onFetchOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Selected time + point count.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pt = points.getOrNull(selectedIdx)
                Text(
                    text = pt?.let { formatLocalTime(it.timestampMs) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (points.isEmpty()) "" else "${points.size} point${if (points.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Scrubber — only shown when there are at least 2 points.
            if (points.size >= 2) {
                Slider(
                    value = selectedIdx.toFloat(),
                    onValueChange = { v ->
                        onSelectIdx(v.roundToInt().coerceIn(0, points.size - 1))
                    },
                    valueRange = 0f..(points.size - 1).toFloat(),
                    steps = (points.size - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        formatLocalTime(points.first().timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatLocalTime(points.last().timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Fetch-older row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp).padding(end = 8.dp),
                    )
                    Spacer(Modifier.size(8.dp))
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

@Composable
private fun DaySelector(
    days: List<DayBucket>,
    selectedKey: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    val now = Clock.System.now().toEpochMilliseconds()
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(days, key = { it.key }) { bucket ->
            FilterChip(
                selected = bucket.key == selectedKey,
                onClick = { onSelect(bucket.key) },
                label = { Text(dayLabel(bucket.key, now)) },
            )
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
