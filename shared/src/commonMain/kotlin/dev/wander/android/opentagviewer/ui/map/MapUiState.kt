package io.github.tieo.taghistory.ui.map

import io.github.tieo.taghistory.data.model.UserMapCameraPosition

/**
 * Compose-facing snapshot of the map screen. Mirrors the live-data tangle
 * in legacy `MapsActivity` — beacon list + per-beacon last-location +
 * refresh status + restored camera — flattened into a single value so the
 * screen is a function of state.
 *
 * Two beacon lists: [markers] is what goes on the map (requires a known
 * location); [cards] is what goes in the bottom strip (all known beacons,
 * even those without a recent report).
 */
data class MapUiState(
    val markers: List<BeaconMarkerUi> = emptyList(),
    val cards: List<TagCardUi> = emptyList(),
    val selectedBeaconId: String? = null,
    val initialCamera: UserMapCameraPosition? = null,
    val isInitialFetchComplete: Boolean = false,
    val isRefreshing: Boolean = false,
    val refreshError: String? = null,
    val requireLogin: Boolean = false,
    /** Beacon IDs whose reports are currently being fetched / decrypted. */
    val fetchingBeaconIds: Set<String> = emptySet(),
)

data class BeaconMarkerUi(
    val beaconId: String,
    val displayName: String,
    val emoji: String?,
    val latitude: Double,
    val longitude: Double,
    val lastUpdatedMs: Long,
    val horizontalAccuracy: Long = 0L,
    val addressLine: String? = null,
)

/**
 * One row in the bottom tag strip. Unlike [BeaconMarkerUi], all location
 * fields are optional so tags the user owns but hasn't had a report for
 * still show up (dimmed, with "No recent location") — parity with what
 * the legacy Java app's `maps_tag_card` did.
 */
data class TagCardUi(
    val beaconId: String,
    val displayName: String,
    val emoji: String?,
    val model: String?,
    val latitude: Double?,
    val longitude: Double?,
    val lastUpdatedMs: Long?,
    val addressLine: String? = null,
)
