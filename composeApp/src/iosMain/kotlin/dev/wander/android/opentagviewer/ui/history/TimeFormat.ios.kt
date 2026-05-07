package io.github.tieo.taghistory.ui.history

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
actual fun formatLocalTime(ms: Long): String =
    Instant.fromEpochMilliseconds(ms).toString().substringAfter('T').take(5)

@OptIn(ExperimentalTime::class)
actual fun formatLocalTimeWithSeconds(ms: Long): String =
    Instant.fromEpochMilliseconds(ms).toString().substringAfter('T').take(8)
