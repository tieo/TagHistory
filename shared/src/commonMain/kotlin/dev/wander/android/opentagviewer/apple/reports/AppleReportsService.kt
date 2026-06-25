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
     * Multi-tag fetch. Efficiency win over the old per-tag path: keys from
     * ALL tags are packed into ONE set of 256-key chunks, so the number of
     * HTTPS round-trips to Apple is governed by the total key count, not the
     * tag count. The old code chunked per tag, which multiplied requests
     * (13 tags ≈ 26 POSTs vs ≈ 16 batched) and made it easy to trip Apple's
     * rate limiter.
     *
     * Key derivation (SHA-256, CPU-bound) still runs per tag in parallel.
     * coroutineScope gives structured concurrency; any failure cancels the
     * batch.
     */
    suspend fun fetchReportsByBeacon(
        beacons: Map<String, FindMyAccessory>,
        from: Instant,
        to: Instant,
    ): Map<String, List<BeaconLocationReport>> = coroutineScope {
        val result = LinkedHashMap<String, MutableList<LocationReport>>()
        for (id in beacons.keys) result[id] = mutableListOf()
        if (beacons.isEmpty()) return@coroutineScope emptyMap()

        // Derive each tag's keys in parallel, then index every hashed key back
        // to its owning beacon + KeyPair so a batched response can be routed.
        val hashedToBeacon = HashMap<String, String>()
        val hashedToKey = HashMap<String, KeyPair>()
        beacons.map { (id, accessory) ->
            async { id to accessory.keysBetween(from - KEY_MARGIN, to + KEY_MARGIN).toList() }
        }.awaitAll().forEach { (id, keys) ->
            for (k in keys) {
                val h = k.hashedAdvKeyB64()
                hashedToBeacon[h] = id
                hashedToKey[h] = k
            }
        }
        if (hashedToBeacon.isEmpty()) return@coroutineScope result.mapValues { emptyList() }

        // Apple ignores the date filter, so send the widest supported range
        // and re-filter client-side.
        val n = now()
        val startMs = (n - FETCH_LOOKBACK).toEpochMilliseconds()
        val endMs = (n + FETCH_MARGIN).toEpochMilliseconds()

        // ONE batched set of 256-key chunks across all tags. Each chunk is a
        // single POST; fan them out concurrently.
        val rawResults = hashedToBeacon.keys.toList().chunked(CHUNK_SIZE).map { ids ->
            async { client.fetchRaw(account, startMs, endMs, ids) }
        }.awaitAll()

        for (raw in rawResults) {
            for (rep in LocationReportsClient.parseReports(raw)) {
                val h = rep.hashedAdvKeyB64()
                val beaconId = hashedToBeacon[h] ?: continue
                val key = hashedToKey[h] ?: continue
                // NOTE: do NOT pre-filter on publishedAt. Apple's response
                // here carries no usable datePublished, so it parses to 0 for
                // every report — a publishedAt-based skip drops everything and
                // the map goes stale. The real time is inside the encrypted
                // payload, so we must decrypt, then filter on the decrypted
                // timestamp.
                rep.decrypt(key)
                val ts = rep.timestamp()
                if (ts < from || ts > to) continue
                result.getValue(beaconId).add(rep)
            }
        }
        result.mapValues { (_, reps) -> toBeaconReports(reps) }
    }

    private suspend fun fetchByKeys(
        keys: List<KeyPair>,
        from: Instant,
        to: Instant,
    ): List<LocationReport> = coroutineScope {
        if (keys.isEmpty()) return@coroutineScope emptyList()

        // Apple's backend ignores the date filter, so we send the widest
        // supported range (last 7 days ± 12h) and re-filter client-side.
        val n = now()
        val startMs = (n - FETCH_LOOKBACK).toEpochMilliseconds()
        val endMs = (n + FETCH_MARGIN).toEpochMilliseconds()

        val hashedToKey = HashMap<String, KeyPair>(keys.size).apply {
            keys.forEach { put(it.hashedAdvKeyB64(), it) }
        }

        // Slice into 256-key chunks and fan them out concurrently — each
        // chunk is one HTTPS round-trip to Apple, so wall time was
        // dominated by serial latency. Decrypt happens after the gather.
        val chunks = buildList {
            var offset = 0
            while (offset < keys.size) {
                val end = minOf(offset + CHUNK_SIZE, keys.size)
                add(keys.subList(offset, end))
                offset = end
            }
        }
        val rawResults = chunks.map { chunk ->
            async {
                val ids = chunk.map { it.hashedAdvKeyB64() }
                client.fetchRaw(account, startMs, endMs, ids)
            }
        }.awaitAll()

        val out = mutableListOf<LocationReport>()
        for (raw in rawResults) {
            for (rep in LocationReportsClient.parseReports(raw)) {
                val match = hashedToKey[rep.hashedAdvKeyB64()] ?: continue
                rep.decrypt(match)
                val ts = rep.timestamp()
                if (ts < from || ts > to) continue
                out += rep
            }
        }
        out
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
