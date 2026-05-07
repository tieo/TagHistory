package io.github.tieo.taghistory.ui.history

/** Format epoch millis as local-time "HH:mm". Platform-provided for timezone correctness. */
expect fun formatLocalTime(ms: Long): String

/** Local-time "HH:mm:ss" used by the sync log. */
expect fun formatLocalTimeWithSeconds(ms: Long): String
