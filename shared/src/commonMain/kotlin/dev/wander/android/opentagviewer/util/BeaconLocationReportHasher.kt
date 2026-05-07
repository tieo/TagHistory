package io.github.tieo.taghistory.util

import io.github.tieo.taghistory.apple.crypto.sha256
import io.github.tieo.taghistory.data.model.BeaconLocationReport

/**
 * Platform hook for 15-decimal Java-`%.15f`-parity formatting. Android +
 * desktop use `BigDecimal(double).setScale(15, HALF_UP)`; native targets
 * fall back to a double-arithmetic approximation (see the iOS actual).
 */
internal expect fun formatDecimal15Finite(value: Double): String

/**
 * Content-addressed hash for a [BeaconLocationReport], keyed by beacon
 * id. Used as the primary key in the `LocationReport` table so duplicate
 * reports from overlapping fetch windows collapse automatically.
 *
 * Format MUST stay byte-identical to the Java implementation — the two
 * codebases will coexist during migration and the on-disk DB carries
 * rows hashed by the Java formatter. `String.format("%.15f", ...)` on
 * JVM produces exactly 15 decimal digits with the platform default
 * rounding; `formatDecimal15` below is a locale-stable reimplementation
 * tuned to match that output byte-for-byte for finite values.
 */
object BeaconLocationReportHasher {

    fun getSha256BytesFor(beaconId: String, report: BeaconLocationReport): ByteArray {
        val encoded = buildString {
            append(beaconId); append('-')
            append(report.status); append('-')
            append(report.timestamp); append('-')
            append(report.publishedAt); append('-')
            append(report.description); append('-')
            append(formatDecimal15(report.latitude)); append('-')
            append(formatDecimal15(report.longitude))
        }
        return sha256(encoded.encodeToByteArray())
    }

    fun getSha256HashFor(beaconId: String, report: BeaconLocationReport): String =
        bytesToHex(getSha256BytesFor(beaconId, report))

    private fun bytesToHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val hi = v ushr 4
            val lo = v and 0xF
            append(if (hi < 10) ('0' + hi) else ('a' + (hi - 10)))
            append(if (lo < 10) ('0' + lo) else ('a' + (lo - 10)))
        }
    }

    /**
     * Analog of Java's `String.format(Locale.ROOT, "%.15f", d)`. Delegates
     * to [formatDecimal15Finite] on finite inputs; non-finites reproduce
     * Java's textual forms so a hash generated after a bad decode still
     * matches historical output.
     */
    internal fun formatDecimal15(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        return formatDecimal15Finite(value)
    }
}
