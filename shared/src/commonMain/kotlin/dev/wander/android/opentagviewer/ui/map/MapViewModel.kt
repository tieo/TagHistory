package io.github.tieo.taghistory.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.sync.SyncEvent
import io.github.tieo.taghistory.sync.SyncLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * KMP ViewModel for the map screen. Drives the state machine the legacy
 * `MapsActivity` implemented inline:
 *
 *  1. Boot — load cached beacons + last-known locations from the DB,
 *     restore the camera position. Emit a "showing stale markers" state.
 *  2. Refresh — call [fetchReports] (platform-injected to let tests stub
 *     out HTTP), persist the returned reports, re-emit updated markers.
 *
 * Reverse geocoding, periodic auto-refresh scheduling, and the bottom
 * swiper's selection semantics all live here too — the activity is a
 * rendering surface only.
 *
 * The viewmodel does NOT own the initial "is the user even logged in"
 * decision — platform hosts check [UserAuthRepository] before deciding to
 * construct it, or call [requireLogin] on sign-out.
 */
class MapViewModel(
    private val beaconRepo: BeaconRepository,
    private val userDataRepo: UserDataRepository,
    private val authRepo: UserAuthRepository,
    /**
     * Production fetches via `AppleReportsService.fetchLastReportsByBeacon`
     * after rehydrating the account. Tests inject a lambda that returns
     * canned reports without touching the network.
     */
    private val fetchReports: suspend (Map<String, BeaconData>, Int) -> Map<String, List<BeaconLocationReport>>,
    /**
     * Optional: given a beacon + its latest location, produce a pretty
     * address line. Null return = show raw coords. Android wires this to
     * the system `Geocoder`; desktop/iOS can leave it as the default null.
     */
    private val reverseGeocode: suspend (Double, Double) -> String? = { _, _ -> null },
    /**
     * Returns the user's last-known device position for the distance sort,
     * or null if unknown. Android wires this to LocationManager's
     * PASSIVE_PROVIDER last-known fix. Null = fall back to recency sort.
     */
    private val currentLocation: () -> Pair<Double, Double>? = { null },
    private val hoursBack: Int = DEFAULT_HOURS_BACK,
    /**
     * Interval between background refresh ticks. Runs in viewModelScope
     * so it stays active for the whole logged-in session — independent
     * of which screen is currently composed (History, Settings, etc.
     * used to silently pause auto-refresh because the timer lived on
     * MapScreen). Set to 0 in tests to disable.
     */
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    private val scope: CoroutineScope? = null,
    /** All DB/IO work hops to this. Tests can inject the test scheduler's dispatcher. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope

    /** Holds the latest location per beacon keyed by beaconId. */
    private val latestLocationByBeacon = mutableMapOf<String, BeaconLocationReport>()

    // Once the user picks a card themselves (swipe / marker tap),
    // refresh stops auto-promoting selection to the most-recently-located beacon.
    // Reset by `reboot()` (post-import) so a fresh import gets the auto-pick again.
    private var userHasExplicitlySelected: Boolean = false

    // Auto-promote of selection to the freshest located beacon is a ONE-SHOT
    // on the first refresh that surfaces any marker. Subsequent refreshes
    // never override the live selection — otherwise periodic ticks that
    // bring different beacons back at different latencies make the pager
    // jump around between cards on its own.
    private var autoPromoteDone: Boolean = false

    /** Cached [BeaconData] so we can reconstruct markers without a re-query. */
    private val beaconsById = mutableMapOf<String, BeaconData>()


    /**
     * Reverse-geocode cache keyed by coordinates rounded to ~110 m (3 d.p.).
     * Without this, [kickoffGeocoding] hits the system Geocoder for every
     * marker on every 60 s refresh — with 7+ tags and the sync Geocoder
     * sometimes taking 100–500 ms per call, that's visible jank.
     */
    private val geocodeCache = mutableMapOf<Long, String>()

    private fun geocodeKey(lat: Double, lon: Double): Long {
        val la = (lat * 1000).toLong()
        val lo = (lon * 1000).toLong()
        return (la shl 32) or (lo and 0xFFFFFFFFL)
    }

    init {
        // Start fetching as soon as the ViewModel is constructed — the
        // hosting AppHost creates the VM eagerly in App.kt, so this kicks
        // off BEFORE MapScreen mounts. Saves the few hundred ms of Compose
        // composition time before the network request starts.
        runScope.launch {
            boot().join()
            // Subscribe to the DB AFTER boot so the first emission merges
            // into already-populated beacon metadata. From here on, ANY
            // writer to the LocationReport table — this VM's refresh, the
            // history screen's fetch, Settings' refresh-now, the
            // background sync worker — flows back into markers/cards
            // automatically. The map is no longer a stale snapshot that
            // only saw locations it fetched itself.
            observeLocations()
            refresh()
            // Periodic refresh lives on the VM (not on MapScreen) so the
            // app keeps pulling new reports while the user is on
            // History / Settings / DeviceInfo too. Cancels with the VM
            // when the user signs out or the process dies.
            if (refreshIntervalMs > 0) {
                while (true) {
                    kotlinx.coroutines.delay(refreshIntervalMs)
                    refresh()
                }
            }
        }
    }

    /**
     * Collect the reactive last-location-per-beacon stream and fold each
     * emission into [latestLocationByBeacon] + re-emit markers/cards.
     * Only adopts a report when it is strictly newer than what we hold,
     * so a concurrent in-flight refresh that already wrote the same row
     * doesn't cause a visible flicker from older-then-newer ordering.
     */
    private fun observeLocations() {
        runScope.launch {
            beaconRepo.observeLastLocationsForAll(ioDispatcher).collect { latest ->
                var changed = false
                for ((id, report) in latest) {
                    val existing = latestLocationByBeacon[id]
                    if (existing == null || report.timestamp > existing.timestamp) {
                        latestLocationByBeacon[id] = report
                        changed = true
                    }
                }
                if (!changed) return@collect
                val (markers, cards) = withContext(ioDispatcher) {
                    buildMarkers() to buildCards()
                }
                _state.update { current ->
                    current.copy(
                        markers = markers,
                        cards = cards,
                        selectedBeaconId = pickSelection(current.selectedBeaconId, markers),
                    )
                }
                kickoffGeocoding()
            }
        }
    }

    /**
     * Boot-time load: auth check, cached beacons, last-known locations,
     * persisted camera. Does NOT trigger a network refresh — the screen
     * calls [refresh] once it's composed so loading feedback is visible.
     *
     * Returns the launched [Job] so callers that need to sequence a
     * subsequent [refresh] can `join()` before calling it — otherwise
     * refresh races boot and clobbers cached markers with empty state.
     */
    fun boot(): Job = runScope.launch {
        val bootData = withContext(ioDispatcher) {
            authRepo.getUserAuth() ?: return@withContext null
            BootData(
                beacons = beaconRepo.getAllBeacons(),
                lastLocations = beaconRepo.getLastLocationsForAll(),
                camera = userDataRepo.getLastCameraPosition(),
            )
        }
        if (bootData == null) {
            _state.update { it.copy(requireLogin = true) }
            return@launch
        }

        beaconsById.clear()
        beaconsById.putAll(bootData.beacons.associateBy { it.beaconId })
        latestLocationByBeacon.clear()
        latestLocationByBeacon.putAll(bootData.lastLocations)

        val (markers, cards) = withContext(ioDispatcher) {
            buildMarkers() to buildCards()
        }
        _state.update {
            it.copy(
                initialCamera = bootData.camera,
                markers = markers,
                cards = cards,
                // Always auto-select the most-recent located beacon (even
                // when a camera position was persisted). Camera restore and
                // tag selection aren't mutually exclusive — the user wants
                // the first card highlighted + map centered on it.
                selectedBeaconId = it.selectedBeaconId
                    ?: markers.maxByOrNull { m -> m.lastUpdatedMs }?.beaconId
                    ?: cards.firstOrNull()?.beaconId,
            )
        }
    }

    private data class BootData(
        val beacons: List<BeaconData>,
        val lastLocations: Map<String, BeaconLocationReport>,
        val camera: UserMapCameraPosition?,
    )

    /**
     * Cascade refresh: fast 1 h sweep first (covers active tags with
     * minimal crypto), then progressively wider windows for stragglers.
     * Each rung only targets tags that haven't been seen yet. Periodic
     * refreshes skip the cascade and just hit the full [hoursBack] window.
     */
    fun refresh() {
        refreshCascade(
            windows = listOf(1, 6, 24),
            // Refresh tick (re-entry every 60 s) picks the widest rung since
            // we already displayed initial data. Only first-run benefits
            // from the 1 h / 6 h / 24 h ladder.
            skipCascadeIfInitialDone = true,
        )
    }

    private fun refreshCascade(windows: List<Int>, skipCascadeIfInitialDone: Boolean) {
        // Always log the user/caller intent so the Settings sync-log UI shows
        // every Refresh-now press, even when an earlier refresh is still in
        // flight and the cascade itself short-circuits.
        SyncLog.record(
            SyncEvent.Kind.START,
            "Refresh started (${beaconsById.size} beacons known)",
            mapOf(
                "beacon_count" to beaconsById.size.toString(),
                "beacon_ids" to beaconsById.keys.joinToString(","),
                "windows" to windows.joinToString(","),
                "skip_cascade_if_initial_done" to skipCascadeIfInitialDone.toString(),
                "is_initial_fetch_complete" to _state.value.isInitialFetchComplete.toString(),
                "user_has_explicit_selection" to userHasExplicitlySelected.toString(),
            ),
        )
        if (_state.value.isRefreshing) {
            SyncLog.record(
                SyncEvent.Kind.INFO,
                "Skipped: previous refresh still in flight",
                mapOf("reason" to "isRefreshing=true"),
            )
            return
        }
        _state.update { it.copy(isRefreshing = true, refreshError = null) }
        // Periodic refresh (after initial fetch done): always re-fetch every
        // beacon with a full window. The cascade's "skip already-located tags"
        // filter only applies during the first-run ladder so stale cached
        // positions get refreshed on every tick.
        val isPeriodic = skipCascadeIfInitialDone && _state.value.isInitialFetchComplete
        val effective = if (isPeriodic) listOf(hoursBack) else windows
        runScope.launch {
            var lastError: String? = null
            for (window in effective) {
                // During the cascade (first-run), only re-query beacons without
                // a recent fix yet. Once a beacon is located in an early rung it
                // doesn't need the heavier wider-window crypto sweep.
                // During periodic refresh, always fetch all so positions stay fresh.
                val toFetch = if (isPeriodic) {
                    beaconsById
                } else {
                    beaconsById.filterKeys { id -> latestLocationByBeacon[id] == null }
                }
                if (toFetch.isEmpty()) {
                    // Cascade completed — all beacons already had cached locations.
                    // Mark initial fetch done so the shimmer card goes away.
                    _state.update { it.copy(isInitialFetchComplete = true) }
                    break
                }

                _state.update { it.copy(fetchingBeaconIds = toFetch.keys.toSet()) }
                SyncLog.record(
                    SyncEvent.Kind.INFO,
                    "Decrypting last ${window}h for ${toFetch.size} beacon(s)",
                    mapOf(
                        "window_h" to window.toString(),
                        "beacons" to toFetch.keys.joinToString(","),
                    ),
                )
                val rungStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val reports = try {
                    withContext(ioDispatcher) { fetchReports(toFetch, window) }
                } catch (e: Exception) {
                    val rungMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - rungStartMs
                    lastError = e.message
                    SyncLog.record(
                        SyncEvent.Kind.RUNG_FAIL,
                        "${window}h rung failed: ${e::class.simpleName}: ${e.message}",
                        mapOf(
                            "window_h" to window.toString(),
                            "is_periodic" to isPeriodic.toString(),
                            "beacons_attempted" to toFetch.size.toString(),
                            "duration_ms" to rungMs.toString(),
                            "error_class" to (e::class.simpleName ?: "?"),
                            "error_msg" to (e.message ?: "?"),
                        ),
                    )
                    _state.update { it.copy(fetchingBeaconIds = emptySet()) }
                    continue
                }
                val (markers, cards) = withContext(ioDispatcher) {
                    if (reports.isNotEmpty()) {
                        beaconRepo.storeToLocationCache(reports)
                        for ((id, list) in reports) {
                            list.maxByOrNull { it.timestamp }?.let { latestLocationByBeacon[id] = it }
                        }
                    }
                    buildMarkers() to buildCards()
                }
                val got = reports.values.sumOf { it.size }
                val rungMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - rungStartMs
                SyncLog.record(
                    SyncEvent.Kind.RUNG_OK,
                    "${window}h rung: ${reports.size}/${toFetch.size} beacons replied, $got reports total",
                    mapOf(
                        "window_h" to window.toString(),
                        "is_periodic" to isPeriodic.toString(),
                        "beacons_attempted" to toFetch.size.toString(),
                        "beacons_replied" to reports.size.toString(),
                        "reports_total" to got.toString(),
                        "duration_ms" to rungMs.toString(),
                        "located_after" to markers.size.toString(),
                        "responding_beacons" to reports.keys.joinToString(","),
                        "non_responding_beacons" to toFetch.keys.minus(reports.keys).joinToString(","),
                    ),
                )
                // Only mark the initial fetch complete once SOMETHING was
                // located in this rung. Otherwise the next rung (6h, then
                // 24h) gets a chance to find older reports. The previous
                // logic flipped the flag after every successful rung
                // regardless of report count, then break'd, so a 1h
                // rung returning 0 reports halted the cascade with
                // "0 located" instead of escalating.
                val anyLocated = markers.isNotEmpty()
                _state.update { current ->
                    current.copy(
                        isInitialFetchComplete = current.isInitialFetchComplete || anyLocated,
                        markers = markers,
                        cards = cards,
                        selectedBeaconId = pickSelection(current.selectedBeaconId, markers),
                    )
                }
                kickoffGeocoding()
                // Stop early only when we actually have a fix on every
                // known beacon — otherwise let the cascade widen to find
                // stragglers. The next periodic tick still covers the
                // long tail.
                if (!isPeriodic &&
                    beaconsById.isNotEmpty() &&
                    beaconsById.keys.all { latestLocationByBeacon[it] != null } &&
                    window != effective.last()
                ) {
                    break
                }
            }
            _state.update {
                it.copy(
                    isRefreshing = false,
                    refreshError = lastError,
                    fetchingBeaconIds = emptySet(),
                    // Cascade has finished; even if no rung located a
                    // beacon, the shimmer card has to come down so the
                    // user sees the (empty) state instead of waiting.
                    isInitialFetchComplete = true,
                )
            }
            val locatedCount = _state.value.markers.size
            SyncLog.record(
                SyncEvent.Kind.REFRESH_DONE,
                if (lastError != null) "Refresh finished with error: $lastError"
                else "Refresh finished ($locatedCount located)",
                mapOf(
                    "located" to locatedCount.toString(),
                    "total_beacons" to beaconsById.size.toString(),
                    "error" to (lastError ?: ""),
                ),
            )
            SyncLog.record(
                SyncEvent.Kind.INFO,
                "Idle — next periodic refresh in 60s",
            )
        }
    }

    fun selectBeacon(beaconId: String?) {
        userHasExplicitlySelected = true
        _state.update { it.copy(selectedBeaconId = beaconId) }
    }

    /**
     * One-shot selection auto-promote. Returns the live selection unless this
     * is the first refresh that surfaced any marker AND the user hasn't picked
     * a card AND the current selection has no location yet — in which case
     * the freshest located beacon wins. Subsequent refreshes leave selection
     * alone, so periodic ticks don't yank the pager between cards.
     */
    private fun pickSelection(
        current: String?,
        markers: List<BeaconMarkerUi>,
    ): String? {
        val hasMarker = current != null && markers.any { it.beaconId == current }
        if (userHasExplicitlySelected || hasMarker || markers.isEmpty() || autoPromoteDone) {
            return current
        }
        autoPromoteDone = true
        return markers.maxByOrNull { it.lastUpdatedMs }?.beaconId ?: current
    }

    /** Persist the camera position after the user pans/zooms. Fire-and-forget. */
    fun saveCamera(position: UserMapCameraPosition) {
        // Update in-state so MapView recreates at the correct position after
        // back-navigation (otherwise MapView rebuilds at world-map default).
        _state.update { it.copy(initialCamera = position) }
        runScope.launch {
            withContext(ioDispatcher) { userDataRepo.storeLastCameraPosition(position) }
        }
    }

    fun requireLogin() {
        _state.update { MapUiState(requireLogin = true) }
    }

    /**
     * Update the user-facing name (and optionally emoji) for a beacon.
     * Persists via UserBeaconOptions and re-emits cards + markers so the
     * map UI updates immediately, no Settings round-trip needed. Empty
     * [name] keeps the existing name (use the dedicated DeviceInfo
     * delete flow to clear it).
     */
    fun renameBeacon(beaconId: String, name: String, emoji: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        runScope.launch {
            withContext(ioDispatcher) {
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                beaconRepo.storeUserBeaconOptions(
                    io.github.tieo.taghistory.db.UserBeaconOptions(
                        beacon_id = beaconId,
                        last_update = now,
                        ui_name = trimmed,
                        ui_emoji = emoji,
                    ),
                )
            }
            refreshNames()
        }
    }

    /**
     * Mark a beacon as removed (soft delete via BeaconNamingRecord +
     * OwnedBeacons.setRemoved). Drops it from cached state and re-emits
     * markers/cards so it disappears from the map immediately.
     */
    fun removeBeacon(beaconId: String) {
        runScope.launch {
            withContext(ioDispatcher) {
                beaconRepo.markBeaconAsRemoved(beaconId)
            }
            beaconsById.remove(beaconId)
            latestLocationByBeacon.remove(beaconId)
            if (_state.value.selectedBeaconId == beaconId) {
                userHasExplicitlySelected = false
            }
            refreshNames()
        }
    }

    /**
     * Cheap re-read of beacon names/emoji from DB. Called every time the
     * map screen returns to the foreground so renames made in DeviceInfo
     * are immediately reflected in cards and markers.
     */
    fun refreshNames() {
        if (beaconsById.isEmpty()) return
        runScope.launch {
            val (markers, cards) = withContext(ioDispatcher) {
                buildMarkers() to buildCards()
            }
            _state.update { it.copy(markers = markers, cards = cards) }
        }
    }

    /**
     * Full reset + reload — call after an import so freshly-stored beacons
     * are picked up. Clears all cached data and reruns boot → refresh.
     */
    fun reboot() {
        beaconsById.clear()
        latestLocationByBeacon.clear()
        geocodeCache.clear()
        userHasExplicitlySelected = false
        autoPromoteDone = false
        _state.update { MapUiState() }
        runScope.launch {
            boot().join()
            refresh()
        }
    }

    private fun buildMarkers(): List<BeaconMarkerUi> {
        val infos = beaconRepo.getAllBeaconInformation()
        return beaconsById.values.mapNotNull { beacon ->
            val loc = latestLocationByBeacon[beacon.beaconId] ?: return@mapNotNull null
            val info = infos[beacon.beaconId]
            BeaconMarkerUi(
                beaconId = beacon.beaconId,
                displayName = info?.displayName ?: beacon.beaconId.take(8),
                emoji = info?.displayEmoji,
                latitude = loc.latitude,
                longitude = loc.longitude,
                lastUpdatedMs = loc.timestamp,
                horizontalAccuracy = loc.horizontalAccuracy,
                addressLine = geocodeCache[geocodeKey(loc.latitude, loc.longitude)],
            )
        }
    }

    /**
     * Every owned beacon shows up as a card — sorted by last-seen descending
     * so the most recently updated tag is always first. Unlocated tags tail.
     */
    private fun buildCards(): List<TagCardUi> {
        val infos = beaconRepo.getAllBeaconInformation()
        val cards = beaconsById.values.map { beacon ->
            val loc = latestLocationByBeacon[beacon.beaconId]
            val info = infos[beacon.beaconId]
            TagCardUi(
                beaconId = beacon.beaconId,
                displayName = info?.displayName ?: beacon.beaconId.take(8),
                emoji = info?.displayEmoji,
                model = info?.model?.takeIf { it.isNotBlank() },
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                lastUpdatedMs = loc?.timestamp,
                addressLine = loc?.let { geocodeCache[geocodeKey(it.latitude, it.longitude)] },
            )
        }
        val (located, unlocated) = cards.partition { it.latitude != null && it.longitude != null }
        return located.sortedByDescending { it.lastUpdatedMs ?: Long.MIN_VALUE } +
            unlocated.sortedByDescending { it.lastUpdatedMs ?: Long.MIN_VALUE }
    }

    private fun kickoffGeocoding() {
        runScope.launch {
            for (marker in _state.value.markers) {
                val key = geocodeKey(marker.latitude, marker.longitude)
                if (geocodeCache.containsKey(key)) continue
                val line = try {
                    reverseGeocode(marker.latitude, marker.longitude)
                } catch (_: Exception) {
                    null
                } ?: continue
                geocodeCache[key] = line
                _state.update { current ->
                    val updatedMarkers = current.markers.map {
                        if (it.beaconId == marker.beaconId) it.copy(addressLine = line) else it
                    }
                    val updatedCards = current.cards.map {
                        if (it.beaconId == marker.beaconId) it.copy(addressLine = line) else it
                    }
                    current.copy(markers = updatedMarkers, cards = updatedCards)
                }
            }
        }
    }

    companion object {
        /**
         * Time window for map refresh. FindMy derives one SHA-256 key per
         * 15-minute slot per tag — 2 h = 8 slots per tag vs 24 h = 96.
         * Map only needs the most recent position, not history, so a short
         * window gives fast first-paint. Tags that reported within 2 h
         * appear quickly; older ones are picked up on subsequent periodic
         * refreshes. History screen uses its own wider window.
         */
        const val DEFAULT_HOURS_BACK: Int = 2
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 60_000L
    }
}
