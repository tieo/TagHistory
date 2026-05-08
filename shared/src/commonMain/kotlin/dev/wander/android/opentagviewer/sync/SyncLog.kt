package io.github.tieo.taghistory.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
data class SyncEvent(
    val timestampMs: Long,
    val kind: Kind,
    val message: String,
    /** Arbitrary key/value detail bag for the panel's expanded view + copy export. */
    val details: Map<String, String> = emptyMap(),
) {
    enum class Kind { START, RUNG_OK, RUNG_FAIL, REFRESH_DONE, INFO }
}

/**
 * In-memory ring buffer of the last N sync events. App-scoped singleton so
 * the Settings "Show debug data" view can read them. Not persisted; resets
 * on process restart, which is fine: this is for live diagnosis, not audit.
 *
 * [environment] is set once at app startup and rendered at the top of the
 * copy export so logs pasted into bug reports include the app/device context.
 */
@OptIn(ExperimentalTime::class)
object SyncLog {
    private const val CAPACITY = 200

    private val _events = MutableStateFlow<List<SyncEvent>>(emptyList())
    val events: StateFlow<List<SyncEvent>> = _events.asStateFlow()

    private val _environment = MutableStateFlow<Map<String, String>>(emptyMap())
    val environment: StateFlow<Map<String, String>> = _environment.asStateFlow()

    fun setEnvironment(env: Map<String, String>) {
        _environment.value = env
    }

    fun record(kind: SyncEvent.Kind, message: String, details: Map<String, String> = emptyMap()) {
        val event = SyncEvent(
            timestampMs = Clock.System.now().toEpochMilliseconds(),
            kind = kind,
            message = message,
            details = details,
        )
        _events.value = (_events.value + event).takeLast(CAPACITY)
    }

    fun clear() {
        _events.value = emptyList()
    }
}
