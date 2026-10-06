package io.github.tieo.taghistory.ui.util

import kotlin.math.roundToInt
import kotlin.time.Instant

// Text every client shows the same way: how long ago, how far, how fast.

/** "48.2094, 9.7203": a location shown as coordinates when no street resolved. */
fun coarseCoords(lat: Double, lon: Double): String {
    fun r(v: Double): String {
        val n = kotlin.math.round(v * 10_000.0).toLong()
        val whole = n / 10_000
        val frac = (kotlin.math.abs(n) % 10_000).toString().padStart(4, '0')
        return "$whole.$frac"
    }
    return "${r(lat)}, ${r(lon)}"
}

/** "Updated 3 h ago" for a tag's newest fix, or "Not yet reported". */
fun lastUpdatedLabel(lastUpdatedMs: Long?, nowMs: Long): String {
    if (lastUpdatedMs == null) return "Not yet reported"
    val s = (nowMs - lastUpdatedMs) / 1_000
    return when {
        s < 60 -> "Updated just now"
        s < 3_600 -> "Updated ${s / 60} min ago"
        s < 86_400 -> "Updated ${s / 3_600} h ago"
        else -> "Updated ${s / 86_400} d ago"
    }
}

/** "5 min ago" within a week, the date after that. */
fun relativeTime(ms: Long, nowMs: Long): String {
    val delta = nowMs - ms
    if (delta < 0) return absoluteDate(ms)
    val s = delta / 1_000
    return when {
        s < 60 -> "just now"
        s < 3_600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3_600} h ago"
        s < 86_400 * 7 -> "${s / 86_400} d ago"
        else -> absoluteDate(ms)
    }
}

/** ISO date (UTC) of [ms]. */
fun absoluteDate(ms: Long): String = Instant.fromEpochMilliseconds(ms).toString().substringBefore('T').take(10)

/** Compact elapsed time for the sync log: "45s", "12m", "3h 5m", "2d 4h". */
fun compactAgo(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
        else -> "${s / 86400}d ${(s % 86400) / 3600}h"
    }
}

fun formatDistance(meters: Double): String =
    if (meters < 1_000.0) "${meters.roundToInt()} m" else "${(meters / 1_000.0).fmtFixed(1)} km"

fun formatDuration(ms: Long): String {
    if (ms < 60_000L) return "${(ms / 1_000L).coerceAtLeast(0)} s"
    val minutes = ms / 60_000L
    if (minutes < 60L) return "$minutes min"
    val hours = minutes / 60L
    val remMin = minutes % 60L
    return if (remMin == 0L) "$hours h" else "$hours h $remMin min"
}

fun formatSpeed(kmh: Double): String = if (kmh < 1.0) "<1 km/h" else "${kmh.roundToInt()} km/h"
