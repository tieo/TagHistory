package io.github.tieo.taghistory.ui.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.taghistory.data.repo.BeaconRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Lives for the Nearby tab. Owns the list of currently-known owned tags
 * (id + display name + emoji) and the most recent BLE hit per id. The
 * actual BLE scanning is platform-only; the host wires it in via
 * [startScan]. The VM just folds events into observable state.
 */
@OptIn(ExperimentalTime::class)
class NearbyViewModel(
    private val beaconRepo: BeaconRepository,
    /** Returns currently-known owned tags. Resolved on screen mount. */
    private val loadOwnedTags: () -> List<OwnedTagInfo>,
    /**
     * Platform-injected BLE start function. Returns a Job that should be
     * cancelled to stop scanning. Each emitted [NearbyHit] is fed into
     * state via the callback.
     */
    private val startBleScan: (
        scope: CoroutineScope,
        onEvent: (NearbyScanEvent) -> Unit,
    ) -> Job,
    /** Host capability probe (UwbCapability on Android, false on stubs). */
    private val uwbAvailable: Boolean = false,
    private val scope: CoroutineScope? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private val _state = MutableStateFlow(NearbyUiState())
    val state: StateFlow<NearbyUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope
    private var scanJob: Job? = null

    fun onStart() {
        val tags = loadOwnedTags()
        _state.update {
            it.copy(
                tags = tags,
                uwbAvailable = uwbAvailable,
                scanState = if (it.scanState == ScanState.IDLE) ScanState.STARTING else it.scanState,
            )
        }
        startScan()
    }

    fun onStop() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanState = ScanState.IDLE) }
    }

    private fun startScan() {
        scanJob?.cancel()
        scanJob = startBleScan(runScope) { event -> handleEvent(event) }
    }

    /** Per-beacon rolling RSSI window so the bar doesn't jitter from one bad sample. */
    private val rssiWindow: MutableMap<String, ArrayDeque<Int>> = HashMap()

    private fun handleEvent(event: NearbyScanEvent) {
        when (event) {
            is NearbyScanEvent.MissingPermission -> _state.update {
                it.copy(scanState = ScanState.PERMISSION_DENIED)
            }
            is NearbyScanEvent.BluetoothOff -> _state.update {
                it.copy(scanState = ScanState.BLUETOOTH_OFF)
            }
            is NearbyScanEvent.Stopped -> _state.update {
                if (it.scanState == ScanState.SCANNING || it.scanState == ScanState.STARTING) {
                    it.copy(scanState = ScanState.IDLE)
                } else it
            }
            is NearbyScanEvent.Hit -> _state.update { current ->
                val window = rssiWindow.getOrPut(event.beaconId) { ArrayDeque() }
                window.addLast(event.rssi)
                while (window.size > RSSI_WINDOW_SIZE) window.removeFirst()
                val smoothed = window.average().toInt()
                val merged = current.hits.toMutableMap()
                merged[event.beaconId] = NearbyHit(
                    beaconId = event.beaconId,
                    rssi = event.rssi,
                    smoothedRssi = smoothed,
                    sampleCount = window.size,
                    keyType = event.keyType,
                    seenAtMs = now(),
                )
                current.copy(
                    hits = merged.toMap(),
                    scanState = ScanState.SCANNING,
                )
            }
        }
    }

    companion object {
        const val RSSI_WINDOW_SIZE: Int = 5
    }
}

enum class ScanState { IDLE, STARTING, SCANNING, PERMISSION_DENIED, BLUETOOTH_OFF }

data class OwnedTagInfo(
    val beaconId: String,
    val displayName: String,
    val emoji: String?,
)

data class NearbyHit(
    val beaconId: String,
    /** Most recent raw RSSI sample. */
    val rssi: Int,
    /** Moving average over the last [NearbyViewModel.RSSI_WINDOW_SIZE] samples. */
    val smoothedRssi: Int,
    val sampleCount: Int,
    val keyType: String,
    val seenAtMs: Long,
)

data class NearbyUiState(
    val tags: List<OwnedTagInfo> = emptyList(),
    val hits: Map<String, NearbyHit> = emptyMap(),
    val scanState: ScanState = ScanState.IDLE,
    /** True if the host reports UWB hardware (Pixel 9 Pro XL etc.). */
    val uwbAvailable: Boolean = false,
)

sealed interface NearbyScanEvent {
    data class Hit(val beaconId: String, val keyType: String, val rssi: Int) : NearbyScanEvent
    data object MissingPermission : NearbyScanEvent
    data object BluetoothOff : NearbyScanEvent
    data object Stopped : NearbyScanEvent
}
