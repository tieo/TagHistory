package io.github.tieo.taghistory.ui.history

import io.github.tieo.taghistory.ui.map.haversineMeters

// How a tag's history reads, shared by every client: which fixes form a real
// move, how a day sums up, the list with leg labels between entries, and the
// day buckets.

/**
 * Walks the (newest-first) entry list, emitting an [EntryItem] for
 * each row and inserting a [LegItem] between any two consecutive
 * entries whose travel exceeds the jitter floor. Same-day check
 * prevents an overnight gap from rendering as a long leg.
 */
fun buildRenderedItems(entries: List<HistoryEntry>): List<RenderedItem> {
    if (entries.isEmpty()) return emptyList()
    val out = mutableListOf<RenderedItem>()
    entries.forEachIndexed { i, e ->
        out += RenderedItem.EntryItem(
            entry = e,
            idx = i,
            isFirst = i == 0,
            isLast = i == entries.lastIndex,
        )
        if (i < entries.lastIndex) {
            val older = entries[i + 1]
            val newerAnchor = entryAnchor(e)
            val olderAnchor = entryAnchor(older)
            val sameDay = localDayStart(newerAnchor.timestampMs) ==
                localDayStart(olderAnchor.timestampMs)
            if (sameDay) {
                val dist = haversineMeters(
                    newerAnchor.latitude, newerAnchor.longitude,
                    olderAnchor.latitude, olderAnchor.longitude,
                )
                val dur = (newerAnchor.timestampMs - olderAnchor.timestampMs)
                    .coerceAtLeast(0L)
                val accFloor = maxOf(
                    newerAnchor.horizontalAccuracy,
                    olderAnchor.horizontalAccuracy,
                )
                if (isRealMove(dist, dur, accFloor)) {
                    out += RenderedItem.LegItem(
                        distanceMeters = dist,
                        durationMs = dur,
                        key = "leg-${e.id}-${older.id}",
                    )
                }
            }
        }
    }
    return out
}

fun entryAnchor(e: HistoryEntry): HistoryPoint = when (e) {
    is HistoryEntry.Stop -> e.anchor
    is HistoryEntry.Move -> e.point
}

sealed class RenderedItem {
    abstract val key: String

    data class EntryItem(
        val entry: HistoryEntry,
        val idx: Int,
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : RenderedItem() {
        override val key: String get() = entry.id
    }

    data class LegItem(
        val distanceMeters: Double,
        val durationMs: Long,
        override val key: String,
    ) : RenderedItem()
}

data class ParsedAddress(val street: String, val city: String?)

/**
 * Split a geocoded address into street + city portions, always
 * dropping the trailing country segment. Geocoder.getAddressLine(0)
 * is typically `"Tulpenweg 44, 89584 Ehingen, Germany"`; we split on
 * commas, drop the last segment as country, and treat the rest as
 * street (first) + city (middle). The history list then either hides
 * the city (if a whole day is in one city) or renders it on a second
 * line beneath the street — never comma-appended, since that read
 * like a single overlong address line.
 */
fun parseAddress(full: String): ParsedAddress {
    val parts = full.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ParsedAddress(full, null)
    val street = parts[0]
    if (parts.size <= 1) return ParsedAddress(street, null)
    val cityParts = parts.drop(1).dropLast(1)
    return ParsedAddress(street, cityParts.joinToString(", ").ifBlank { null })
}

/**
 * If every entry in this day's address list resolves to the same
 * city, return that city — the history header already shows the day
 * so repeating "Ehingen" on every row is noise. If addresses span
 * multiple cities (or none have been geocoded yet) returns null and
 * the row renderer falls back to showing the city on each line.
 */
fun commonCityOrNull(entries: List<HistoryEntry>): String? {
    val cities = entries.mapNotNull { e ->
        val raw = when (e) {
            is HistoryEntry.Stop -> e.anchor.address
            is HistoryEntry.Move -> e.point.address
        }
        raw?.let { parseAddress(it).city }
    }
    if (cities.isEmpty()) return null
    val first = cities.first()
    return if (cities.all { it == first }) first else null
}

data class DaySummary(
    val distanceMeters: Double,
    val movingMs: Long,
    val stopCount: Int,
)

fun buildDaySummary(points: List<HistoryPoint>): DaySummary {
    if (points.size < 2) {
        return DaySummary(
            distanceMeters = 0.0,
            movingMs = 0L,
            stopCount = points.count { it.kind == HistoryPointKind.STOP }.let {
                if (it > 0) 1 else 0
            },
        )
    }
    var distance = 0.0
    var movingMs = 0L
    var stops = 0
    var inStop = false
    for (i in points.indices) {
        val p = points[i]
        if (p.kind == HistoryPointKind.STOP && !inStop) {
            stops++; inStop = true
        } else if (p.kind != HistoryPointKind.STOP) {
            inStop = false
        }
        if (i == 0) continue
        val prev = points[i - 1]
        val d = haversineMeters(prev.latitude, prev.longitude, p.latitude, p.longitude)
        val dt = (p.timestampMs - prev.timestampMs).coerceAtLeast(0L)
        val accFloor = maxOf(prev.horizontalAccuracy, p.horizontalAccuracy)
        if (isRealMove(d, dt, accFloor)) {
            distance += d
            // The fix-to-fix gap dt is mostly the tag SITTING at the
            // previous location — only the tail end was actual travel.
            // Cap each leg's "moving time" contribution at the time
            // it would take to walk the distance (5 km/h ≈ 1.4 m/s).
            // Without this, a 200 m hop after 4 h of sitting still
            // credited the full 4 h as "moving time".
            val walkMs = (d / 1.4 * 1000.0).toLong()
            movingMs += minOf(dt, walkMs)
        }
    }
    return DaySummary(distance, movingMs, stops)
}

/** Anything smaller than this is below the "interesting motion" UX floor. */
private const val MIN_MOVE_METERS = 20.0

/**
 * Borderline leg (dist between MULT_FLOOR and MULT_TRUSTED times the
 * accuracy) must clear MIN_SUSTAINED_KMH average speed.
 */
private const val MIN_MOVE_ACCURACY_MULT_FLOOR = 2.0

/** Above this ratio, the leg is clearly larger than GPS noise — trust it regardless of duration. */
private const val MIN_MOVE_ACCURACY_MULT_TRUSTED = 5.0

/** Hard cap. Above this, the fix is teleporting and we treat it as a bad sample. */
private const val MAX_PLAUSIBLE_KMH = 250.0

/** Borderline-leg average-speed floor. Below this, treat as stationary drift over a long window. */
private const val MIN_SUSTAINED_KMH = 1.5

/**
 * Single source of truth for "is this leg real movement, not GPS
 * jitter?" — applied to the day summary, the MoveRow accuracy
 * subline and the rail leg-label so all three agree.
 *
 * Decision tree:
 *  1. distance < 20 m -> jitter (UX floor).
 *  2. distance < 2 * max(accuracy) -> jitter (well within noise radius).
 *  3. speed > 250 km/h -> jitter (teleport).
 *  4. distance >= 5 * max(accuracy) -> real (clearly larger than noise,
 *     trust regardless of duration — handles "tag was stationary for
 *     a long time, then moved 30 m right before this report").
 *  5. otherwise borderline -> real only if average speed is at least
 *     a slow walk (1.5 km/h). Filters out a 30 m drift accumulated
 *     across two hours by a tag that never actually went anywhere.
 */
fun isRealMove(
    distanceMeters: Double,
    durationMs: Long,
    accuracyFloorMeters: Long,
): Boolean {
    if (distanceMeters < MIN_MOVE_METERS) return false
    if (distanceMeters < accuracyFloorMeters * MIN_MOVE_ACCURACY_MULT_FLOOR) return false
    val speedKmh = if (durationMs > 0)
        (distanceMeters / 1000.0) / (durationMs / 3_600_000.0)
    else Double.POSITIVE_INFINITY
    if (speedKmh > MAX_PLAUSIBLE_KMH) return false
    if (distanceMeters >= accuracyFloorMeters * MIN_MOVE_ACCURACY_MULT_TRUSTED) return true
    return speedKmh >= MIN_SUSTAINED_KMH
}

fun buildDayBuckets(points: List<HistoryPoint>): List<DayBucket> =
    points.groupBy { localDayStart(it.timestampMs) }
        .entries
        .sortedByDescending { it.key }
        .map { (k, v) -> DayBucket(key = k, points = v) }

fun dayLabel(dayStartMs: Long, nowMs: Long, todayStr: String, yesterdayStr: String): String {
    val nowStart = localDayStart(nowMs)
    return when {
        dayStartMs == nowStart -> todayStr
        dayStartMs == nowStart - DAY_MS -> yesterdayStr
        else -> formatLocalDate(dayStartMs)
    }
}

data class DayBucket(
    val key: Long,
    val points: List<HistoryPoint>,
)

const val DAY_MS: Long = 24L * 60L * 60L * 1000L
