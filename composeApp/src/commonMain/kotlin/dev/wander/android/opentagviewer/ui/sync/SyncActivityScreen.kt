package io.github.tieo.taghistory.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tieo.taghistory.data.repo.SyncOutcome
import io.github.tieo.taghistory.data.repo.SyncRun
import io.github.tieo.taghistory.ui.history.formatLocalDate
import io.github.tieo.taghistory.ui.history.formatLocalTimeWithSeconds
import io.github.tieo.taghistory.ui.nav.PushedScreenScaffold

/**
 * Durable view of background-sync attempts. Answers "did the background
 * actually run and store data" over days -- the raw SyncLog only reached logcat
 * and vanished. Rows come newest-first from [io.github.tieo.taghistory.data.repo.SyncRunRepository].
 */
@Composable
fun SyncActivityScreen(
    runs: List<SyncRun>,
    onBack: () -> Unit,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    PushedScreenScaffold(title = "Sync activity", onBack = onBack, modifier = modifier) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            SyncActivityHeader(runs, nowMs)
            HorizontalDivider()
            if (runs.isEmpty()) {
                Text(
                    "No background sync recorded yet.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("sync_activity_list")) {
                    items(runs, key = { it.startedAtMs }) { run ->
                        SyncRunRow(run)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncActivityHeader(runs: List<SyncRun>, nowMs: Long) {
    val lastEffective = runs.firstOrNull { it.outcome != SyncOutcome.SKIPPED }
    val lastSuccess = runs.firstOrNull { it.outcome == SyncOutcome.SUCCESS }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Background sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (lastSuccess != null) {
                "Last successful sync ${ago(nowMs - lastSuccess.startedAtMs)} ago"
            } else {
                "No successful background sync recorded yet"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (lastEffective != null && lastEffective !== lastSuccess) {
            Text(
                "Last attempt ${ago(nowMs - lastEffective.startedAtMs)} ago (${lastEffective.outcome.name.lowercase()})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${runs.size} recorded run(s). Times are local.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncRunRow(run: SyncRun) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${formatLocalDate(run.startedAtMs)}  ${formatLocalTimeWithSeconds(run.startedAtMs)}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                buildString {
                    append(run.trigger.name.lowercase())
                    when (run.outcome) {
                        SyncOutcome.SUCCESS ->
                            append("  •  ${run.persistedReports} report(s), ${run.beaconCount} tag(s)")
                        SyncOutcome.RETRY ->
                            append("  •  ${run.detail ?: "failed"}")
                        SyncOutcome.SKIPPED ->
                            append("  •  ${run.detail ?: "skipped"}")
                    }
                    run.windowHours?.let { append("  •  ${it}h window") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutcomeBadge(run.outcome)
    }
}

@Composable
private fun OutcomeBadge(outcome: SyncOutcome) {
    val (label, color) = when (outcome) {
        SyncOutcome.SUCCESS -> "OK" to Color(0xFF2E7D32)
        SyncOutcome.RETRY -> "RETRY" to MaterialTheme.colorScheme.error
        SyncOutcome.SKIPPED -> "SKIP" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun ago(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
        else -> "${s / 86400}d ${(s % 86400) / 3600}h"
    }
}
