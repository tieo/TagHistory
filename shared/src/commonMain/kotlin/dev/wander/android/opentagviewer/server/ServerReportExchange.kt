package io.github.tieo.taghistory.server

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.db.TagHistoryDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Trades location reports between the standalone app and a sync server.
 * The app keeps fetching from Apple itself; this only sends what it fetched
 * and takes in what the server and other devices fetched, so either side
 * holds the full history. Beacons are left alone: the phone's rows carry
 * the keys it fetches and scans with, the server's are redacted.
 *
 * Both directions page by SQLite rowid. Uploads walk the local table after
 * an upload cursor; downloads follow the server's seq. Downloaded rows get
 * local rowids too, and when nothing local was waiting to go up the upload
 * cursor skips past them, so they are not sent straight back. Cursors
 * belong to one server database: a new [ServerStatus.instanceId] restarts
 * both, which also hands a freshly set up server the phone's whole history.
 */
class ServerReportExchange(
    private val db: TagHistoryDatabase,
    private val cursors: Settings,
) {
    data class Result(val uploaded: Int, val downloaded: Int)

    private val lock = Mutex()

    suspend fun exchange(client: ServerClient): Result = lock.withLock {
        val status = client.status()
        if (cursors.getStringOrNull(KEY_INSTANCE) != status.instanceId) {
            cursors.putLong(KEY_UPLOAD, 0L)
            cursors.putLong(KEY_DOWNLOAD, 0L)
            cursors.putString(KEY_INSTANCE, status.instanceId)
        }

        var uploaded = 0
        while (true) {
            val rows = db.locationReportQueries
                .replicationPage(cursors.getLong(KEY_UPLOAD, 0L), UPLOAD_PAGE.toLong())
                .executeAsList()
            if (rows.isEmpty()) break
            uploaded += client.uploadReports(
                rows.map {
                    ReportUpload(
                        beaconId = it.beacon_id,
                        publishedAt = it.published_at,
                        description = it.description,
                        timestamp = it.timestamp,
                        confidence = it.confidence,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        horizontalAccuracy = it.horizontal_accuracy,
                        status = it.status,
                    )
                },
            ).stored
            cursors.putLong(KEY_UPLOAD, rows.last().seq)
        }

        var downloaded = 0
        do {
            val page = client.reports(cursors.getLong(KEY_DOWNLOAD, 0L))
            downloaded += store(page.reports)
            // Saved per page: an interrupted exchange resumes where it stopped.
            cursors.putLong(KEY_DOWNLOAD, page.next)
        } while (page.hasMore)

        Result(uploaded = uploaded, downloaded = downloaded)
    }

    /** Inserts the reports this device lacks and returns how many it lacked. */
    private fun store(reports: List<ReportDto>): Int {
        if (reports.isEmpty()) return 0
        var added = 0L
        db.transaction {
            val before = maxRowid()
            for (r in reports) {
                added += db.locationReportQueries.insertIfAbsent(
                    hashId = r.hashId,
                    beaconId = r.beaconId,
                    publishedAt = r.publishedAt,
                    description = r.description,
                    timestamp = r.timestamp,
                    confidence = r.confidence,
                    latitude = r.latitude,
                    longitude = r.longitude,
                    horizontalAccuracy = r.horizontalAccuracy,
                    status = r.status,
                    lastUpdate = r.lastUpdate,
                ).value
            }
            // Inside the transaction no other writer can slip a local row in
            // between, so everything above `before` came from the server.
            if (cursors.getLong(KEY_UPLOAD, 0L) >= before) cursors.putLong(KEY_UPLOAD, maxRowid())
        }
        return added.toInt()
    }

    private fun maxRowid(): Long = db.locationReportQueries.maxSeq().executeAsOne()

    private companion object {
        const val KEY_INSTANCE = "exchange_instance_id"
        const val KEY_UPLOAD = "exchange_upload_cursor"
        const val KEY_DOWNLOAD = "exchange_download_cursor"
        const val UPLOAD_PAGE = 1_000
    }
}
