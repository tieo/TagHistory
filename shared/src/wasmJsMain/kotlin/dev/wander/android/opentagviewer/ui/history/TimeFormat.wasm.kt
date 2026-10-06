package io.github.tieo.taghistory.ui.history

// Browser uses JS Date for local-tz formatting. kotlinx-datetime would
// be cleaner but adding it to the wasm classpath is out of scope here.

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

private external interface JsDate : JsAny {
    fun getFullYear(): Int
    fun getMonth(): Int
    fun getDate(): Int
    fun getHours(): Int
    fun getMinutes(): Int
    fun getSeconds(): Int
    fun setHours(h: Int, m: Int, s: Int, ms: Int): Double
    fun getTime(): Double
}

private fun newDate(ms: Double): JsDate = js("new Date(ms)")

actual fun formatLocalTime(ms: Long): String {
    val d = newDate(ms.toDouble())
    return "${pad2(d.getHours())}:${pad2(d.getMinutes())}"
}

actual fun formatLocalTimeWithSeconds(ms: Long): String {
    val d = newDate(ms.toDouble())
    return "${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}"
}

actual fun localDayStart(ms: Long): Long {
    val d = newDate(ms.toDouble())
    d.setHours(0, 0, 0, 0)
    return d.getTime().toLong()
}

actual fun formatLocalDate(ms: Long): String {
    val d = newDate(ms.toDouble())
    return "${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}"
}
