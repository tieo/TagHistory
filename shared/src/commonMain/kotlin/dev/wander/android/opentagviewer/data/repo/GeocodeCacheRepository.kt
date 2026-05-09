package io.github.tieo.taghistory.data.repo

import io.github.tieo.taghistory.db.TagHistoryDatabase
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Persistent reverse-geocode cache. Same coordinates resolved once across
 * the entire lifetime of the app, regardless of which beacon's history
 * triggered the lookup. Coordinates are rounded to 5 decimal places (~1 m)
 * before becoming the key so that nearby reports collapse onto one entry.
 */
@OptIn(ExperimentalTime::class)
class GeocodeCacheRepository(
    private val db: TagHistoryDatabase,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    fun get(lat: Double, lon: Double): String? = runCatching {
        val key = roundedKey(lat, lon)
        val hit = db.geocodeCacheQueries.getByKey(key).executeAsOneOrNull() ?: return@runCatching null
        // Best-effort touch; not transactional with the read.
        runCatching { db.geocodeCacheQueries.touch(nowMs(), key) }
        hit
    }.getOrNull()

    fun getMany(coords: Collection<Pair<Double, Double>>): Map<String, String> = runCatching {
        if (coords.isEmpty()) return@runCatching emptyMap()
        val keys = coords.map { (lat, lon) -> roundedKey(lat, lon) }.distinct()
        db.geocodeCacheQueries.getByKeys(keys).executeAsList()
            .associate { it.rounded_key to it.address }
    }.getOrElse { emptyMap() }

    fun put(lat: Double, lon: Double, address: String) {
        runCatching {
            db.geocodeCacheQueries.upsert(roundedKey(lat, lon), address, nowMs())
        }
    }

    /** Stable cache key matching the precision used everywhere else. */
    fun keyFor(lat: Double, lon: Double): String = roundedKey(lat, lon)

    /** Drop entries older than [olderThanMs] from now. Cheap to call on boot. */
    fun evictOlderThan(olderThanMs: Long) {
        db.geocodeCacheQueries.evictOlderThan(nowMs() - olderThanMs)
    }

    private fun roundedKey(lat: Double, lon: Double): String {
        val rLat = round(lat * 100_000.0) / 100_000.0
        val rLon = round(lon * 100_000.0) / 100_000.0
        return "$rLat,$rLon"
    }
}
