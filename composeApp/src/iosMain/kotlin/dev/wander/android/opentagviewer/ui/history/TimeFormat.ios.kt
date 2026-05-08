package io.github.tieo.taghistory.ui.history

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
actual fun formatLocalTime(ms: Long): String =
    Instant.fromEpochMilliseconds(ms).toString().substringAfter('T').take(5)

@OptIn(ExperimentalTime::class)
actual fun formatLocalTimeWithSeconds(ms: Long): String =
    Instant.fromEpochMilliseconds(ms).toString().substringAfter('T').take(8)

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

@OptIn(ExperimentalTime::class)
actual fun localDayStart(ms: Long): Long = ms - (ms % DAY_MS)

@OptIn(ExperimentalTime::class)
actual fun formatLocalDate(ms: Long): String =
    Instant.fromEpochMilliseconds(ms).toString().substringBefore('T').take(10)
