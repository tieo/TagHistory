package io.github.tieo.taghistory.ui.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HHmm = DateTimeFormatter.ofPattern("HH:mm")
private val HHmmss = DateTimeFormatter.ofPattern("HH:mm:ss")
private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

actual fun formatLocalTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HHmm)

actual fun formatLocalTimeWithSeconds(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HHmmss)

actual fun localDayStart(ms: Long): Long =
    Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

actual fun formatLocalDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(ISO_DATE)
