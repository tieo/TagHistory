package io.github.tieo.taghistory.ui.history

/** Format epoch millis as local-time "HH:mm". Platform-provided for timezone correctness. */
expect fun formatLocalTime(ms: Long): String

/** Local-time "HH:mm:ss" used by the sync log. */
expect fun formatLocalTimeWithSeconds(ms: Long): String

/** Epoch-millis of the start of the local-tz day that contains [ms]. */
expect fun localDayStart(ms: Long): Long

/** "YYYY-MM-DD" in local timezone for the day containing [ms]. */
expect fun formatLocalDate(ms: Long): String
