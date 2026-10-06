package io.github.tieo.taghistory.ui.util

import kotlin.math.abs
import kotlin.math.round

/**
 * Tiny wasm-safe replacement for `"%.Nf".format(value)`. Kotlin's
 * `String.format` is JVM-only; commonMain code shared with wasmJs
 * cannot use it. Used by lat/lon labels and the "1.2 km" distance
 * pill — millimetre-level accuracy is not required.
 */
fun Double.fmtFixed(decimals: Int): String {
    if (this.isNaN()) return "NaN"
    if (this.isInfinite()) return if (this > 0) "Infinity" else "-Infinity"
    val sign = if (this < 0) "-" else ""
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = round(abs(this) * scale).toLong()
    val whole = scaled / scale
    val frac = scaled % scale
    return if (decimals == 0) "$sign$whole"
    else "$sign$whole.${frac.toString().padStart(decimals, '0')}"
}
