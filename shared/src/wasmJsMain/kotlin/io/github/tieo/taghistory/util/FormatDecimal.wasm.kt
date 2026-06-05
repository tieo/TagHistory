package io.github.tieo.taghistory.util

import kotlin.math.abs
import kotlin.math.floor

/**
 * Best-effort wasmJs approximation of Java's `%.15f` formatting. The
 * report hasher should not run on web today (no DB to write into), but
 * we still need an actual so the file compiles.
 */
internal actual fun formatDecimal15Finite(value: Double): String {
    val sign = if (value < 0.0) "-" else ""
    val v = abs(value)
    val whole = floor(v).toLong()
    var fracPart = v - whole.toDouble()
    val sb = StringBuilder()
    sb.append(sign)
    sb.append(whole.toString())
    sb.append('.')
    repeat(15) {
        fracPart *= 10.0
        val digit = floor(fracPart).toInt().coerceIn(0, 9)
        sb.append(('0' + digit))
        fracPart -= digit.toDouble()
    }
    return sb.toString()
}
