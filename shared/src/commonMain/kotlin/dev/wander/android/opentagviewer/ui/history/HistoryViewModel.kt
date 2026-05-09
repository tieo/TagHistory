package io.github.tieo.taghistory.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * KMP ViewModel for the per-beacon location history. The legacy
 * `HistoryViewActivity` both fetched recent history via the reports
 * service and rendered what was already cached — this port keeps the
 * render half and defers the fetch to the platform host via a lambda so
 * common code stays HTTP-free.
 */
@OptIn(ExperimentalTime::class)
class HistoryViewModel(
    private val beaconRepo: BeaconRepository,
    private val beaconId: String,
    /** Optional: fetch additional reports for a date range. */
    private val fetchRange: suspend (String, Long, Long) -> List<BeaconLocationReport> =
        { _, _, _ -> emptyList() },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope

    fun load(startUnixMs: Long, endUnixMs: Long) {
        _state.update { it.copy(rangeStartMs = startUnixMs, rangeEndMs = endUnixMs) }
        runScope.launch { emitPoints() }
    }

    fun loadLast24h() {
        val end = nowMs()
        val start = end - DAY_MS
        load(start, end)
    }

    fun fetchAndLoad(startUnixMs: Long, endUnixMs: Long) {
        _state.update {
            it.copy(
                rangeStartMs = startUnixMs,
                rangeEndMs = endUnixMs,
                isLoading = true,
                error = null,
            )
        }
        runScope.launch {
            try {
                val fetched = fetchRange(beaconId, startUnixMs, endUnixMs)
                if (fetched.isNotEmpty()) {
                    withContext(ioDispatcher) {
                        beaconRepo.storeToLocationCache(mapOf(beaconId to fetched))
                    }
                }
                emitPoints()
                _state.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Fetch failed")
                }
            }
        }
    }

    private suspend fun emitPoints() {
        val start = _state.value.rangeStartMs ?: return
        val end = _state.value.rangeEndMs ?: return
        val points = withContext(ioDispatcher) {
            beaconRepo.getLocationsFor(beaconId, start, end)
                .sortedByDescending { it.timestamp }
                .map { it.toUi() }
        }
        _state.update { it.copy(points = points) }
    }

    private fun BeaconLocationReport.toUi(): HistoryPoint = HistoryPoint(
        timestampMs = timestamp,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracy = horizontalAccuracy,
    )

    private companion object {
        const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    }
}

data class HistoryUiState(
    val rangeStartMs: Long? = null,
    val rangeEndMs: Long? = null,
    val points: List<HistoryPoint> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class HistoryPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Long,
)
