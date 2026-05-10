package io.github.tieo.taghistory.ui.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Locale-aware: "8 May 2026" / "May 8, 2026" / "8. Mai 2026" / "8 мая
// 2026" depending on the device locale, instead of always-ISO. Pulled
// from Locale.getDefault() so changing the system language updates the
// label on the next composition.
private fun localizedDateFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())

private fun localizedTimeFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())

private fun localizedTimeWithSecondsFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())

actual fun formatLocalTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(localizedTimeFormatter())

actual fun formatLocalTimeWithSeconds(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(localizedTimeWithSecondsFormatter())

actual fun localDayStart(ms: Long): Long =
    Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

actual fun formatLocalDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(localizedDateFormatter())
