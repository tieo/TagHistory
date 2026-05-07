package io.github.tieo.taghistory.apple.reports

import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import io.github.tieo.taghistory.apple.findmy.KeyPair
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

/**
 * High-level fetch + decrypt coordinator. Mirrors
 * `LocationReportsFetcher` + `AsyncAppleAccount.fetch_reports`.
 *
 * Responsibilities:
 *  - Materialize the rolling key set for a given [FindMyAccessory] within
 *    `[from - 12h, to + 12h]` (Python's margin).
 *  - Chunk the keys 256 at a time so we stay under Apple's per-request
 *    id cap.
 *  - Clamp the server-side date range to "now ± 7d12h / +12h" because
 *    Apple's backend ignores the date filter (biemster/FindMy#7). Results
 *    are re-filtered client-side.
 *  - Pre-decrypt each returned [LocationReport] with its matching key.
 *  - Emit either a flat list per accessory or a map keyed by beacon id.
 */
@OptIn(ExperimentalTime::class)
class AppleReportsService(
    private val client: LocationReportsClient,
    private val account: AppleAccount,
    /** Test seam — fixed clock in unit tests, system clock in production. */
    private val now: () -> Instant = { Clock.System.now() },
) {

    suspend fun fetchReports(
        accessory: FindMyAccessory,
        from: Instant,
        to: Instant,
    ): List<LocationReport> {
        val keys = accessory.keysBetween(from - KEY_MARGIN, to + KEY_MARGIN)
        return fetchByKeys(keys.toList(), from, to)
    }

    suspend fun fetchLastReports(
        accessory: FindMyAccessory,
        hoursBack: Int,
    ): List<LocationReport> {
        val to = now()
        val from = to - hoursBack.hours
        return fetchReports(accessory, from, to)
    }

    suspend fun fetchLastReportsByBeacon(
        beacons: Map<String, FindMyAccessory>,
        hoursBack: Int,
    ): Map<String, List<BeaconLocationReport>> {
        val to = now()
        val from = to - hoursBack.hours
        return fetchReportsByBeacon(beacons, from, to)
    }

    /**
     * Per-tag fetch runs in parallel — key derivation is CPU-bound
     * (SHA-256 × slot-count × tag-count) and serialising it was a big
     * chunk of first-paint latency. coroutineScope gives structured
     * concurrency; any tag's failure cancels the batch.
     */
    suspend fun fetchReportsByBeacon(
        beacons: Map<String, FindMyAccessory>,
        from: Instant,
        to: Instant,
    ): Map<String, List<BeaconLocationReport>> = coroutineScope {
        val deferred = beacons.map { (id, accessory) ->
            async {
                id to toBeaconReports(fetchReports(accessory, from, to))
            }
        }
        deferred.awaitAll().toMap(linkedMapOf())
    }

    private suspend fun fetchByKeys(
        keys: List<KeyPair>,
        from: Instant,
        to: Instant,
    ): List<LocationReport> {
        if (keys.isEmpty()) return emptyList()

        // Apple's backend ignores the date filter, so we send the widest
        // supported range (last 7 days ± 12h) and re-filter client-side.
        val n = now()
        val startMs = (n - FETCH_LOOKBACK).toEpochMilliseconds()
        val endMs = (n + FETCH_MARGIN).toEpochMilliseconds()

        val hashedToKey = HashMap<String, KeyPair>(keys.size).apply {
            keys.forEach { put(it.hashedAdvKeyB64(), it) }
        }

        val out = mutableListOf<LocationReport>()
        var offset = 0
        while (offset < keys.size) {
            val end = minOf(offset + CHUNK_SIZE, keys.size)
            val chunk = keys.subList(offset, end)
            val ids = chunk.map { it.hashedAdvKeyB64() }

            val raw = client.fetchRaw(account, startMs, endMs, ids)
            for (rep in LocationReportsClient.parseReports(raw)) {
                val match = hashedToKey[rep.hashedAdvKeyB64()] ?: continue
                rep.decrypt(match)
                val ts = rep.timestamp()
                if (ts < from || ts > to) continue
                out += rep
            }
            offset = end
        }
        return out
    }

    companion object {
        private const val CHUNK_SIZE = 256
        private val KEY_MARGIN: Duration = 12.hours
        private val FETCH_MARGIN: Duration = 12.hours
        private val FETCH_LOOKBACK: Duration = 7.days + FETCH_MARGIN

        /**
         * Map [LocationReport]s to the flat view-model shape the UI
         * consumes, sorted chronologically (newest last).
         */
        private fun toBeaconReports(reports: List<LocationReport>): List<BeaconLocationReport> {
            val sorted = reports.sortedBy { it.timestamp() }
            return sorted.map { r ->
                BeaconLocationReport(
                    publishedAt = r.publishedAt.toEpochMilliseconds(),
                    description = r.description,
                    timestamp = r.timestamp().toEpochMilliseconds(),
                    confidence = r.confidence().toLong(),
                    latitude = r.latitude(),
                    longitude = r.longitude(),
                    horizontalAccuracy = r.horizontalAccuracy().toLong(),
                    status = r.status().toLong(),
                )
            }
        }
    }
}
