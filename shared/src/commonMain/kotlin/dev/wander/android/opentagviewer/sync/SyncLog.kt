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
) {
    enum class Kind { START, RUNG_OK, RUNG_FAIL, REFRESH_DONE, INFO }
}

/**
 * In-memory ring buffer of the last N sync events. App-scoped singleton so
 * the Settings "Show debug data" view can read them. Not persisted; resets
 * on process restart, which is fine: this is for live diagnosis, not audit.
 */
@OptIn(ExperimentalTime::class)
object SyncLog {
    private const val CAPACITY = 100

    private val _events = MutableStateFlow<List<SyncEvent>>(emptyList())
    val events: StateFlow<List<SyncEvent>> = _events.asStateFlow()

    fun record(kind: SyncEvent.Kind, message: String) {
        val event = SyncEvent(
            timestampMs = Clock.System.now().toEpochMilliseconds(),
            kind = kind,
            message = message,
        )
        _events.value = (_events.value + event).takeLast(CAPACITY)
    }

    fun clear() {
        _events.value = emptyList()
    }
}
