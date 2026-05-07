package io.github.tieo.taghistory.data.repo

import io.github.tieo.taghistory.data.importer.BeaconInformationParser
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.model.BeaconInformation
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.ImportData
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.DailyHistoryFetchRecord
import io.github.tieo.taghistory.db.Import
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.UserBeaconOptions
import io.github.tieo.taghistory.util.BeaconCombinerUtil
import io.github.tieo.taghistory.util.BeaconLocationReportHasher
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Kotlin port of the Java `BeaconRepository`. Suspend-function API
 * instead of RxJava3 observables — callers live in coroutines anyway, and
 * this keeps common-main free of platform threading assumptions.
 *
 * The underlying SQLDelight driver is synchronous on both Android and
 * JVM desktop, so these functions don't internally switch dispatchers.
 * Call sites should wrap in `withContext(Dispatchers.IO)` if they're on
 * the main thread.
 */
@OptIn(ExperimentalTime::class)
class BeaconRepository(
    private val db: TagHistoryDatabase,
    /** Test seam for deterministic last_update timestamps. */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /**
     * In-memory cache of parsed beacon info, rebuilt any time the
     * underlying tables change. Reads from the cache are O(1) — the XML
     * plist parse only runs once per import / option change, not once
     * per UI recomposition.
     */
    private var infoCache: Map<String, BeaconInformation>? = null

    private fun invalidateInfoCache() {
        infoCache = null
    }

    /**
     * Insert an import plus its dependent beacon + naming rows in one
     * transaction. The child rows get their `importId` set to the
     * DB-assigned primary key.
     */
    fun addNewImport(importData: ImportData): ImportData {
        var updated: ImportData = importData
        db.transaction {
            val row = importData.anImport
            db.importQueries.insert(
                version = row.version,
                importedAt = row.imported_at,
                exportedAt = row.exported_at,
                sourceUser = row.source_user,
                via = row.via,
            )
            val newId = db.importQueries.lastInsertedId().executeAsOne()

            for (ob in importData.ownedBeacons) {
                db.ownedBeaconQueries.upsert(
                    id = ob.id,
                    importId = newId,
                    content = ob.content,
                    version = ob.version,
                    isRemoved = ob.is_removed,
                )
            }
            for (nr in importData.beaconNamingRecords) {
                db.beaconNamingRecordQueries.upsert(
                    id = nr.id,
                    importId = newId,
                    version = nr.version,
                    content = nr.content,
                    isRemoved = nr.is_removed,
                )
            }

            updated = importData.copy(
                anImport = row.copy(id = newId),
                ownedBeacons = importData.ownedBeacons.map { it.copy(import_id = newId) },
                beaconNamingRecords = importData.beaconNamingRecords.map { it.copy(import_id = newId) },
            )
        }
        invalidateInfoCache()
        return updated
    }

    fun getImportById(importId: Long): Import? =
        db.importQueries.getById(importId).executeAsOneOrNull()

    /** All non-removed beacons with their naming + user-option overlays. */
    fun getAllBeacons(): List<BeaconData> {
        val owned = db.ownedBeaconQueries.getAll().executeAsList()
        val naming = db.beaconNamingRecordQueries.getAll().executeAsList()
        val options = db.userBeaconOptionsQueries.getAll().executeAsList()
        return BeaconCombinerUtil.combine(owned, naming, options)
    }

    fun storeUserBeaconOptions(options: UserBeaconOptions) {
        db.userBeaconOptionsQueries.upsert(
            beaconId = options.beacon_id,
            lastUpdate = options.last_update,
            uiName = options.ui_name,
            uiEmoji = options.ui_emoji,
        )
        invalidateInfoCache()
    }

    fun getById(beaconId: String): BeaconData? {
        val owned = db.ownedBeaconQueries.getById(beaconId).executeAsOneOrNull() ?: return null
        val naming = db.beaconNamingRecordQueries.getByBeaconId(beaconId).executeAsOneOrNull()
        val options = db.userBeaconOptionsQueries.getById(beaconId).executeAsOneOrNull()
        return BeaconData(
            beaconId = owned.id,
            ownedBeaconInfo = owned,
            beaconNamingRecord = naming,
            userBeaconOptions = options,
        )
    }

    /** Upserts every report across every beacon, stamped with `now`. */
    fun storeToLocationCache(
        reportsForBeaconId: Map<String, List<BeaconLocationReport>>,
    ): Map<String, List<BeaconLocationReport>> {
        if (reportsForBeaconId.isEmpty()) return reportsForBeaconId

        val now = nowMs()
        db.transaction {
            for ((beaconId, reports) in reportsForBeaconId) {
                for (r in reports) {
                    val hash = BeaconLocationReportHasher.getSha256HashFor(beaconId, r)
                    db.locationReportQueries.upsert(
                        hashId = hash,
                        beaconId = beaconId,
                        publishedAt = r.publishedAt,
                        description = r.description,
                        timestamp = r.timestamp,
                        confidence = r.confidence,
                        latitude = r.latitude,
                        longitude = r.longitude,
                        horizontalAccuracy = r.horizontalAccuracy,
                        status = r.status,
                        lastUpdate = now,
                    )
                }
            }
        }
        return reportsForBeaconId
    }

    /**
     * Most recent report per beacon. MAX(timestamp)-per-group query —
     * SQLite pulls the row matching the max correctly because we select
     * the aggregated column and the other columns aren't grouped-ambiguous
     * in the way that matters for our inputs.
     */
    fun getLastLocationsForAll(): Map<String, BeaconLocationReport> {
        val rows = db.locationReportQueries.getLastForAllBeacons().executeAsList()
        return rows.associate { r ->
            r.beacon_id to BeaconLocationReport(
                publishedAt = r.published_at,
                description = r.description.orEmpty(),
                timestamp = r.timestamp!!,
                confidence = r.confidence,
                latitude = r.latitude,
                longitude = r.longitude,
                horizontalAccuracy = r.horizontal_accuracy,
                status = r.status,
            )
        }
    }

    fun getLocationsFor(
        beaconId: String,
        startUnixMs: Long,
        endUnixMs: Long,
    ): List<BeaconLocationReport> =
        db.locationReportQueries.getInTimeRange(beaconId, startUnixMs, endUnixMs)
            .executeAsList()
            .map { r ->
                BeaconLocationReport(
                    publishedAt = r.published_at,
                    description = r.description.orEmpty(),
                    timestamp = r.timestamp,
                    confidence = r.confidence,
                    latitude = r.latitude,
                    longitude = r.longitude,
                    horizontalAccuracy = r.horizontal_accuracy,
                    status = r.status,
                )
            }

    fun storeHistoryRecords(vararg records: DailyHistoryFetchRecord): List<DailyHistoryFetchRecord> {
        val now = nowMs()
        val stamped = records.map { it.copy(last_update = now) }
        db.transaction {
            for (r in stamped) {
                db.dailyHistoryFetchRecordQueries.upsert(
                    dayStartTime = r.day_start_time,
                    beaconId = r.beacon_id,
                    lastUpdate = r.last_update,
                )
            }
        }
        return stamped
    }

    fun markBeaconAsRemoved(beaconId: String) {
        db.transaction {
            db.beaconNamingRecordQueries.setRemoved(beaconId)
            db.ownedBeaconQueries.setRemoved(beaconId)
        }
        invalidateInfoCache()
    }

    /** Lazily-computed map of beacon id → parsed info. */
    fun getAllBeaconInformation(): Map<String, BeaconInformation> {
        infoCache?.let { return it }
        val rows = getAllBeacons()
        val built = rows.mapNotNull { BeaconInformationParser.parse(it) }
            .associateBy { it.beaconId }
        infoCache = built
        return built
    }

    fun getInformationFor(beaconId: String): BeaconInformation? =
        getAllBeaconInformation()[beaconId]
            ?: getById(beaconId)?.let { BeaconInformationParser.parse(it) }

    fun getInsertionHistoryItem(
        beaconId: String,
        startOfDayTimestampMs: Long,
    ): DailyHistoryFetchRecord? =
        db.dailyHistoryFetchRecordQueries.getIfExists(beaconId, startOfDayTimestampMs)
            .executeAsOneOrNull()
}
