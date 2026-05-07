package io.github.tieo.taghistory.ui.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HHmm = DateTimeFormatter.ofPattern("HH:mm")
private val HHmmss = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun formatLocalTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HHmm)

actual fun formatLocalTimeWithSeconds(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HHmmss)
