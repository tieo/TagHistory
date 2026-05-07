package io.github.tieo.taghistory.util

import kotlin.math.floor

/**
 * iOS fallback. No BigDecimal analog in native stdlib — we split into
 * whole+fractional and scale only the fractional part, which fits in a
 * double without loss (unlike `abs * 1e15` for |x| >= ~10). Not
 * guaranteed byte-identical to Java %.15f on edge cases, but existing
 * rows were all written by the Android app so hashes generated here
 * stay self-consistent inside the iOS install.
 */
internal actual fun formatDecimal15Finite(value: Double): String {
    val isNegative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    val abs = if (isNegative) -value else value

    val whole = abs.toLong()
    val fractional = abs - whole.toDouble()
    val scaledFrac = fractional * 1e15
    val roundedFrac = floor(scaledFrac + 0.5).toLong()

    val (outWhole, outFrac) = if (roundedFrac == 1_000_000_000_000_000L) {
        (whole + 1L) to 0L
    } else {
        whole to roundedFrac
    }

    val fracStr = outFrac.toString().padStart(15, '0')
    return (if (isNegative) "-" else "") + outWhole.toString() + "." + fracStr
}
