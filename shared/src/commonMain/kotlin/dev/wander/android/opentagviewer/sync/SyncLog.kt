package io.github.tieo.taghistory.sync

/**
 * Sync activity logger. One log, read it with `adb logcat | grep TagHistory`.
 *
 * IMPORTANT: do NOT println() here. On many Android builds System.out is
 * NOT routed to logcat — it was silently dropped on the user's Pixel, so
 * snackbars showed errors (e.g. HTTP 503) that never appeared in
 * `adb logcat`. Instead the platform installs a real [sink] at startup:
 * Android wires android.util.Log, which always lands in logcat. Default
 * sink stays println for desktop/test/wasm where stdout IS the log.
 */
class SyncEvent {
    enum class Kind { START, RUNG_OK, RUNG_FAIL, REFRESH_DONE, INFO }
}

object SyncLog {
    /**
     * Platform log sink. Android sets this to android.util.Log.i in
     * Application.onCreate so every record lands in logcat. Volatile so the
     * startup write is visible to the background worker thread.
     */
    @Volatile
    var sink: (String) -> Unit = { println(it) }

    fun record(kind: SyncEvent.Kind, message: String, details: Map<String, String> = emptyMap()) {
        val detail = if (details.isEmpty()) {
            ""
        } else {
            " " + details.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        sink("[SyncLog] $kind: $message$detail")
    }
}
