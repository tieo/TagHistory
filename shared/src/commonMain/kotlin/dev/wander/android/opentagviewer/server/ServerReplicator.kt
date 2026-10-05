package io.github.tieo.taghistory.server

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.Import
import io.github.tieo.taghistory.db.LocationReport
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.UserBeaconOptions
import io.github.tieo.taghistory.data.repo.SyncRunRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Copies the server's tables into the client's local database, so every
 * screen reads local rows exactly as it does in the standalone app.
 *
 * Reports and sync runs are append-mostly and pulled incrementally after a
 * cursor kept in [cursors]; beacons are a small set and pulled whole. The
 * cursors belong to one server database ([ServerStatus.instanceId]); when the
 * id changes they restart from zero and the local copy is filled again.
 * Pulls are serialized, so a pull started while one runs waits for it and
 * then picks up whatever arrived in between.
 */
class ServerReplicator(
    private val client: ServerClient,
    private val db: TagHistoryDatabase,
    private val beaconRepo: BeaconRepository,
    private val cursors: Settings,
    private val nowMs: () -> Long,
) {
    data class PullResult(
        val status: ServerStatus,
        /** The beacon set, names or overrides changed. */
        val beaconsChanged: Boolean,
        val newReports: Int,
    )

    private val lock = Mutex()

    suspend fun pull(): PullResult = lock.withLock {
        val status = client.status()
        if (cursors.getStringOrNull(KEY_INSTANCE) != status.instanceId) {
            cursors.putLong(KEY_REPORTS, 0L)
            cursors.putLong(KEY_RUNS, 0L)
            cursors.putString(KEY_INSTANCE, status.instanceId)
        }

        val snapshot = client.beacons()
        val beaconsChanged = beaconRepo.applyServerSnapshot(
            imports = snapshot.imports.map { it.toRow() },
            owned = snapshot.owned.map { it.toRow() },
            naming = snapshot.naming.map { it.toRow() },
            options = snapshot.options.map { it.toRow() },
        )

        var after = cursors.getLong(KEY_REPORTS, 0L)
        var newReports = 0
        do {
            val page = client.reports(after)
            beaconRepo.storeReplicatedReports(page.reports.map { it.toRow() })
            newReports += page.reports.size
            after = page.next
            // Saved per page: an interrupted pull resumes where it stopped.
            cursors.putLong(KEY_REPORTS, after)
        } while (page.hasMore)

        val runs = client.syncRuns(cursors.getLong(KEY_RUNS, 0L))
        if (runs.isNotEmpty()) {
            db.transaction {
                for (r in runs) {
                    db.syncRunRecordQueries.upsertReplicated(
                        id = r.id,
                        startedAt = r.startedAt,
                        triggerKind = r.trigger,
                        outcome = r.outcome,
                        detail = r.detail,
                        persistedReports = r.persistedReports,
                        beaconCount = r.beaconCount,
                        windowHours = r.windowHours,
                        durationMs = r.durationMs,
                    )
                }
                db.syncRunRecordQueries.pruneOlderThan(nowMs() - SyncRunRepository.RETENTION_MS)
            }
            cursors.putLong(KEY_RUNS, runs.maxOf { it.id })
        }

        PullResult(status = status, beaconsChanged = beaconsChanged, newReports = newReports)
    }

    private companion object {
        const val KEY_INSTANCE = "server_instance_id"
        const val KEY_REPORTS = "server_reports_cursor"
        const val KEY_RUNS = "server_sync_runs_cursor"
    }
}

internal fun ImportDto.toRow() = Import(id, version, importedAt, exportedAt, sourceUser, via)

internal fun OwnedBeaconDto.toRow() = OwnedBeacons(id, importId, content, version, false)

internal fun NamingRecordDto.toRow() = BeaconNamingRecord(id, importId, version, content, false)

internal fun BeaconOptionsDto.toRow() = UserBeaconOptions(beaconId, lastUpdate, uiName, uiEmoji)

internal fun ReportDto.toRow() = LocationReport(
    hash_id = hashId,
    beacon_id = beaconId,
    published_at = publishedAt,
    description = description,
    timestamp = timestamp,
    confidence = confidence,
    latitude = latitude,
    longitude = longitude,
    horizontal_accuracy = horizontalAccuracy,
    status = status,
    last_update = lastUpdate,
)
