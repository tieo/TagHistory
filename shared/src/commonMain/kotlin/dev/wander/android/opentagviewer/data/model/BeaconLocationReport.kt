package io.github.tieo.taghistory.data.model

/**
 * View-model shape for a decrypted FindMy report. Matches the fields on
 * the Java `BeaconLocationReport` DTO that the UI and DB layer already
 * consume — kept intentionally flat and immutable for easy round-tripping
 * through SQLDelight and the Compose layer.
 *
 * All timestamps are UNIX epoch milliseconds.
 *
 * Primary reference: `findmy/reports/reports.py#LocationReport`
 * (FindMy.py 0.7.6).
 */
data class BeaconLocationReport(
    val publishedAt: Long,
    val description: String,
    val timestamp: Long,
    val confidence: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Long,
    val status: Long,
)
