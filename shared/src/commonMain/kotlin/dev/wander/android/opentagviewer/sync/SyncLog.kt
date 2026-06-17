package io.github.tieo.taghistory.sync

/**
 * Sync activity logger. Writes to the platform log ONLY — on Android
 * println() lands in logcat under the System.out tag. There is
 * deliberately no in-app buffer or Settings panel: an app can't read
 * its own logcat without the privileged READ_LOGS permission, so a
 * second in-app log can only diverge from logcat. One log. Read it with
 * `adb logcat | grep '\[SyncLog\]'`.
 */
class SyncEvent {
    enum class Kind { START, RUNG_OK, RUNG_FAIL, REFRESH_DONE, INFO }
}

object SyncLog {
    fun record(kind: SyncEvent.Kind, message: String, details: Map<String, String> = emptyMap()) {
        val detail = if (details.isEmpty()) {
            ""
        } else {
            " " + details.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        println("[SyncLog] $kind: $message$detail")
    }
}
