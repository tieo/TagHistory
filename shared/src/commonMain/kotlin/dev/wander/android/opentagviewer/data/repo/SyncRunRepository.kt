package io.github.tieo.taghistory.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.tieo.taghistory.db.TagHistoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Which trigger fired a background sync attempt. */
enum class SyncTrigger { WORKER, ALARM, MANUAL }

/**
 * Result of a sync attempt. SKIPPED means the pass decided not to fetch at all
 * (disabled, no auth, nothing loadable, or throttled behind a recent run) — it
 * is distinct from a SUCCESS that fetched and stored zero new reports.
 */
enum class SyncOutcome { SUCCESS, RETRY, SKIPPED }

data class SyncRun(
    val startedAtMs: Long,
    val trigger: SyncTrigger,
    val outcome: SyncOutcome,
    val detail: String?,
    val persistedReports: Int,
    val beaconCount: Int,
    val windowHours: Int?,
    val durationMs: Long?,
)

/**
 * Persists one row per background sync attempt so the app can show, over days,
 * whether the background actually ran and stored data. The raw [SyncLog] only
 * ever reached logcat and vanished with the buffer; this is the durable record
 * the Sync-activity screen reads.
 *
 * Rows older than [RETENTION_MS] are pruned on every write so the table can't
 * grow without bound.
 */
@OptIn(ExperimentalTime::class)
class SyncRunRepository(
    private val db: TagHistoryDatabase,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val queries get() = db.syncRunRecordQueries

    fun record(run: SyncRun) {
        db.transaction {
            queries.insert(
                startedAt = run.startedAtMs,
                triggerKind = run.trigger.name,
                outcome = run.outcome.name,
                detail = run.detail,
                persistedReports = run.persistedReports.toLong(),
                beaconCount = run.beaconCount.toLong(),
                windowHours = run.windowHours?.toLong(),
                durationMs = run.durationMs,
            )
            queries.pruneOlderThan(nowMs() - RETENTION_MS)
        }
    }

    /** Epoch ms of the newest non-skipped run, or null if there is none. */
    fun lastEffectiveAtMs(): Long? = queries.lastEffectiveAt().executeAsOneOrNull()

    fun observeRecent(limit: Long = 100, context: CoroutineContext = Dispatchers.Default): Flow<List<SyncRun>> =
        queries.recent(limit).asFlow().mapToList(context).map { rows -> rows.map { it.toDomain() } }

    private fun io.github.tieo.taghistory.db.SyncRunRecord.toDomain() = SyncRun(
        startedAtMs = started_at,
        trigger = runCatching { SyncTrigger.valueOf(trigger_kind) }.getOrDefault(SyncTrigger.WORKER),
        outcome = runCatching { SyncOutcome.valueOf(outcome) }.getOrDefault(SyncOutcome.SUCCESS),
        detail = detail,
        persistedReports = persisted_reports.toInt(),
        beaconCount = beacon_count.toInt(),
        windowHours = window_hours?.toInt(),
        durationMs = duration_ms,
    )

    companion object {
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
