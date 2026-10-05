package io.github.tieo.taghistory.ui.deviceinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.taghistory.data.model.BeaconInformation
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconEditor
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.LocalBeaconEditor
import io.github.tieo.taghistory.db.UserBeaconOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Per-beacon detail screen: display name / emoji, beacon id, latest
 * location report, and edit actions. The legacy `DeviceInfoActivity`
 * merged in a small map surface and an emoji picker; this port exposes
 * the data, the UI chooses how to render.
 */
@OptIn(ExperimentalTime::class)
class DeviceInfoViewModel(
    private val beaconRepo: BeaconRepository,
    private val beaconId: String,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val scope: CoroutineScope? = null,
    private val editor: BeaconEditor = LocalBeaconEditor(beaconRepo),
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceInfoUiState())
    val state: StateFlow<DeviceInfoUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope

    fun load() {
        runScope.launch {
            val info = beaconRepo.getInformationFor(beaconId)
            val lastLocation = beaconRepo.getLastLocationsForAll()[beaconId]
            _state.update {
                it.copy(
                    beaconId = beaconId,
                    displayName = info?.displayName ?: beaconId.take(8),
                    emoji = info?.displayEmoji,
                    info = info,
                    lastLocation = lastLocation,
                    notFound = info == null,
                )
            }
        }
    }

    fun rename(newName: String, newEmoji: String?) {
        runScope.launch {
            edit {
                editor.setOptions(
                    UserBeaconOptions(
                        beacon_id = beaconId,
                        last_update = nowMs(),
                        ui_name = newName,
                        ui_emoji = newEmoji,
                    )
                )
            } ?: return@launch
            load()
        }
    }

    fun remove() {
        runScope.launch {
            edit { editor.remove(beaconId) } ?: return@launch
            _state.update { it.copy(removed = true) }
        }
    }

    fun dismissEditError() = _state.update { it.copy(editError = null) }

    /**
     * Runs an edit; a failure (the server unreachable or refusing) leaves
     * the screen as it was and is shown instead of escaping the scope.
     */
    private suspend fun edit(block: suspend () -> Unit): Unit? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        _state.update { it.copy(editError = e.message ?: e::class.simpleName ?: "Error") }
        null
    }

}

data class DeviceInfoUiState(
    val beaconId: String = "",
    val displayName: String = "",
    val emoji: String? = null,
    val info: BeaconInformation? = null,
    val lastLocation: BeaconLocationReport? = null,
    val notFound: Boolean = false,
    val removed: Boolean = false,
    /** Why the last rename or removal failed, until dismissed. */
    val editError: String? = null,
)
