package io.github.tieo.taghistory.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * JVM (and Android) match Java's `%.15f` exactly by going through
 * `BigDecimal(double)` — same intermediate representation Formatter
 * uses — with `HALF_UP` rounding. Equivalent to
 * `String.format(Locale.ROOT, "%.15f", d)` for finite inputs but costs
 * one fewer Formatter dispatch.
 */
internal actual fun formatDecimal15Finite(value: Double): String {
    // Route through String.format for completeness — cheaper than our own
    // BigDecimal wrapper once the JIT warms up, and guaranteed-identical
    // to the Java hasher whose output we must stay byte-compatible with.
    return String.format(Locale.ROOT, "%.15f", value)
}

// Keep BigDecimal imports referenced so IDE doesn't drop them if anyone
// swaps the body for the BigDecimal path (kept as reference impl).
@Suppress("unused")
private fun bigDecimalImpl(value: Double): String =
    BigDecimal(value).setScale(15, RoundingMode.HALF_UP).toPlainString()
