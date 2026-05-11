package io.github.tieo.taghistory.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.GeocodeCacheRepository
import io.github.tieo.taghistory.util.PerfTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Owns everything visible on the History screen except actual rendering:
 *
 *   - DB read of cached reports for a time range,
 *   - background network fetch + persist (kept off Main via ioDispatcher),
 *   - persistent reverse-geocoding with sync DB cache hits + parallel
 *     resolve of misses, deduped by rounded coordinate,
 *   - stop / move classification per point (Haversine + dwell time),
 *   - "trip"-style entry grouping where consecutive STOP-classified
 *     points collapse into a single [HistoryEntry.Stop] with arrival,
 *     departure and an expandable list of constituent points,
 *   - filters (stops-only, hide-low-accuracy) re-applied without going
 *     back to the DB,
 *   - per-day summary (distance, moving time, stop count).
 */
@OptIn(ExperimentalTime::class)
class HistoryViewModel(
    private val beaconRepo: BeaconRepository,
    private val beaconId: String,
    private val fetchRange: suspend (String, Long, Long) -> List<BeaconLocationReport> =
        { _, _, _ -> emptyList() },
    private val realReverseGeocode: (suspend (Double, Double) -> String?)? = null,
    private val geocodeCache: GeocodeCacheRepository? = null,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * Tz-aware "day-bucket key" function. Used by [buildEntries] to
     * detect when consecutive points cross a local-day boundary so the
     * cross-day distance/duration of the first point of each day isn't
     * shown as if it were a regular move. Default implementation is the
     * identity, so unit tests that don't care about day boundaries
     * still get sensible behaviour; the Android host injects the real
     * implementation backed by [java.time]'s system-zone day start.
     */
    private val localDayStart: (Long) -> Long = { it },
    private val scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val geocodeConcurrency: Int = 16,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope

    private var observeJob: kotlinx.coroutines.Job? = null

    /**
     * Cancel the DB-flow subscription started by [load]. Production
     * relies on the parent scope's cancellation (via [ViewModel.onCleared]);
     * tests that pass an external [scope] cancel manually so `runTest`
     * doesn't trip its "active child jobs" detector.
     */
    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

    fun load(startUnixMs: Long, endUnixMs: Long) {
        PerfTrace.mark("vm.load() called")
        _state.update { it.copy(rangeStartMs = startUnixMs, rangeEndMs = endUnixMs) }
        // First do a one-shot DB read so the screen has data on the
        // first frame, then subscribe to the DB query Flow so any new
        // point landing in LocationReport (from the map screen's
        // periodic refresh, the manual refresh, the background worker)
        // re-emits into the history list automatically. The
        // subscription is dropped one emission because asFlow re-fires
        // the same initial snapshot we just read.
        observeJob?.cancel()
        observeJob = runScope.launch {
            emitPoints()
            kickoffGeocoding()
            var first = true
            beaconRepo.observeLocationsFor(beaconId, startUnixMs, endUnixMs, ioDispatcher)
                .collect { rows ->
                    if (first) { first = false; return@collect }
                    PerfTrace.mark("observeLocationsFor emitted rows=${rows.size}")
                    onRowsChanged(rows)
                    kickoffGeocoding()
                }
        }
    }

    fun loadLast24h() {
        val end = nowMs()
        val start = end - DAY_MS
        load(start, end)
    }

    fun fetchAndLoad(startUnixMs: Long, endUnixMs: Long) {
        PerfTrace.mark("vm.fetchAndLoad() called")
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
                val fetched = withContext(ioDispatcher) {
                    fetchRange(beaconId, startUnixMs, endUnixMs)
                }
                PerfTrace.mark("network fetch done (${fetched.size})")
                if (fetched.isNotEmpty()) {
                    withContext(ioDispatcher) {
                        beaconRepo.storeToLocationCache(mapOf(beaconId to fetched))
                    }
                    PerfTrace.mark("storeToLocationCache done")
                }
                emitPoints()
                PerfTrace.mark("post-fetch emitPoints done")
                _state.update { it.copy(isLoading = false) }
                kickoffGeocoding()
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Fetch failed")
                }
            }
        }
    }

    /** UI hook: pull-to-refresh / the explicit Retry button. */
    fun refresh() {
        val start = _state.value.rangeStartMs ?: return
        val end = _state.value.rangeEndMs ?: return
        fetchAndLoad(start, end)
    }

    fun setStopsOnly(value: Boolean) {
        if (_state.value.filters.stopsOnly == value) return
        _state.update {
            val newFilters = it.filters.copy(stopsOnly = value)
            it.copy(
                filters = newFilters,
                entries = buildEntries(it.points, newFilters),
            )
        }
    }

    fun setHideLowAccuracy(value: Boolean) {
        if (_state.value.filters.hideLowAccuracy == value) return
        _state.update {
            val newFilters = it.filters.copy(hideLowAccuracy = value)
            it.copy(
                filters = newFilters,
                entries = buildEntries(it.points, newFilters),
            )
        }
    }

    /**
     * Shared "build the visible state from raw DB rows" pipeline.
     * Called both by the SQLDelight Flow subscription (live updates)
     * and by [emitPoints] on demand.
     */
    private suspend fun onRowsChanged(rows: List<BeaconLocationReport>) {
        val (points, entries) = withContext(ioDispatcher) {
            val sorted = rows.sortedBy { it.timestamp }
            val cache = geocodeCache
            val cached: Map<String, String> = if (cache != null) {
                cache.getMany(sorted.map { it.latitude to it.longitude })
            } else emptyMap()
            val mapped = sorted.map { it.toUi(cached, cache) }
            val classified = classify(mapped.sortedByDescending { it.timestampMs })
            val entries = buildEntries(classified, _state.value.filters)
            classified to entries
        }
        _state.update { it.copy(points = points, entries = entries) }
    }

    private suspend fun emitPoints() {
        val start = _state.value.rangeStartMs ?: return
        val end = _state.value.rangeEndMs ?: return
        val (points, entries) = withContext(ioDispatcher) {
            PerfTrace.mark("emitPoints DB read start")
            val rows = beaconRepo.getLocationsFor(beaconId, start, end)
            PerfTrace.mark("emitPoints DB rows=${rows.size}")
            val sorted = rows.sortedBy { it.timestamp }
            val cache = geocodeCache
            val cached: Map<String, String> = if (cache != null) {
                cache.getMany(sorted.map { it.latitude to it.longitude })
            } else emptyMap()
            val mapped = sorted.map { it.toUi(cached, cache) }
            // Newest-first list as the public point sequence; classify
            // works on the chronological flip.
            val classified = classify(mapped.sortedByDescending { it.timestampMs })
            val entries = buildEntries(classified, _state.value.filters)
            PerfTrace.mark("emitPoints mapped + classified n=${classified.size} entries=${entries.size}")
            classified to entries
        }
        _state.update { it.copy(points = points, entries = entries) }
    }

    private fun BeaconLocationReport.toUi(
        cachedByKey: Map<String, String>,
        cache: GeocodeCacheRepository?,
    ): HistoryPoint {
        val key = cache?.keyFor(latitude, longitude)
        return HistoryPoint(
            id = hashId
                ?: "$timestamp|$latitude|$longitude|$horizontalAccuracy|$status",
            timestampMs = timestamp,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracy = horizontalAccuracy,
            address = key?.let { cachedByKey[it] },
            kind = HistoryPointKind.MOVE,
        )
    }

    /**
     * Walks the (newest-first) list and labels runs of points within
     * STOP_RADIUS_M of one another and spanning at least STOP_MIN_MS as
     * stops. Anything else is a movement waypoint.
     */
    private fun classify(points: List<HistoryPoint>): List<HistoryPoint> {
        if (points.size < 2) return points.map { it.copy(kind = HistoryPointKind.STOP) }
        val out = ArrayList<HistoryPoint>(points.size)
        val chrono = points.asReversed()
        var clusterStartIdx = 0
        var i = 1
        while (i <= chrono.size) {
            val end = i
            val anchor = chrono[clusterStartIdx]
            val current = chrono.getOrNull(i)
            val outOfCluster = current == null ||
                haversineMeters(anchor.latitude, anchor.longitude,
                    current.latitude, current.longitude) > STOP_RADIUS_M
            if (outOfCluster) {
                val clusterEnd = end - 1
                val span = chrono[clusterEnd].timestampMs - chrono[clusterStartIdx].timestampMs
                val isStop = span >= STOP_MIN_MS && (clusterEnd - clusterStartIdx) >= 1
                for (k in clusterStartIdx..clusterEnd) {
                    out += chrono[k].copy(
                        kind = if (isStop) HistoryPointKind.STOP else HistoryPointKind.MOVE,
                    )
                }
                clusterStartIdx = i
            }
            i++
        }
        return out.asReversed()
    }

    private fun kickoffGeocoding() {
        val real = realReverseGeocode ?: return
        val cache = geocodeCache
        runScope.launch {
            PerfTrace.mark("kickoffGeocoding start")
            val current = _state.value.points
            val keyToPoints = HashMap<String, MutableList<HistoryPoint>>()
            for (p in current) {
                if (p.address != null) continue
                val key = cache?.keyFor(p.latitude, p.longitude)
                    ?: "${p.latitude},${p.longitude}"
                keyToPoints.getOrPut(key) { mutableListOf() }.add(p)
            }
            if (keyToPoints.isEmpty()) {
                PerfTrace.mark("kickoffGeocoding nothing to do")
                return@launch
            }
            PerfTrace.mark("geocoding unique keys=${keyToPoints.size}")
            val gate = Semaphore(geocodeConcurrency)
            coroutineScope {
                for ((_, group) in keyToPoints) {
                    val anchor = group.first()
                    async {
                        gate.withPermit {
                            val resolved = runCatching {
                                real(anchor.latitude, anchor.longitude)
                            }.getOrNull() ?: return@withPermit
                            cache?.put(anchor.latitude, anchor.longitude, resolved)
                            val ids = group.mapTo(HashSet(group.size)) { it.id }
                            _state.update { state ->
                                val patchedPoints = state.points.map { p ->
                                    if (p.id in ids) p.copy(address = resolved) else p
                                }
                                state.copy(
                                    points = patchedPoints,
                                    entries = buildEntries(patchedPoints, state.filters),
                                )
                            }
                        }
                    }
                }
            }
            PerfTrace.mark("kickoffGeocoding all groups done")
        }
    }

    /**
     * Group the classified points (newest-first) into [HistoryEntry]s.
     *
     * - Consecutive STOP-classified points become a single
     *   [HistoryEntry.Stop] with arrival = oldest member, departure =
     *   newest member, dwell = departure − arrival.
     * - Each MOVE-classified point becomes one [HistoryEntry.Move]
     *   carrying the distance + duration since the previous (older)
     *   point so the UI can show "12 km/h" without reaching back into
     *   the raw list.
     *
     * Filters:
     *   - `stopsOnly` drops the Move entries.
     *   - `hideLowAccuracy` drops points with horizontalAccuracy >
     *     threshold before grouping (so a noisy GPS fix in the middle
     *     of a stop doesn't spuriously break the cluster).
     */
    private fun buildEntries(
        points: List<HistoryPoint>,
        filters: HistoryFilters,
    ): List<HistoryEntry> {
        if (points.isEmpty()) return emptyList()
        val effective = points.filter { p ->
            !(filters.hideLowAccuracy &&
                p.horizontalAccuracy > filters.accuracyThresholdMeters)
        }
        if (effective.isEmpty()) return emptyList()

        // Iterate chronologically so arrival/departure read intuitively;
        // then reverse the entry list at the end so the UI keeps its
        // newest-first convention.
        val chrono = effective.sortedBy { it.timestampMs }
        val out = mutableListOf<HistoryEntry>()
        var stopBuf: MutableList<HistoryPoint>? = null
        var prev: HistoryPoint? = null

        fun flushStop() {
            val buf = stopBuf
            if (buf.isNullOrEmpty()) {
                stopBuf = null
                return
            }
            val first = buf.first()
            val last = buf.last()
            // Pick the address from the first non-null member; in
            // practice they should all share one because the cluster
            // is within ~25 m, but be defensive.
            val addr = buf.firstNotNullOfOrNull { it.address }
            out += HistoryEntry.Stop(
                id = "stop-${first.id}-${last.id}",
                timestampMs = last.timestampMs,
                arrivalMs = first.timestampMs,
                departureMs = last.timestampMs,
                anchor = last.copy(address = addr),
                members = buf.toList(),
            )
            stopBuf = null
        }

        for (p in chrono) {
            if (p.kind == HistoryPointKind.STOP) {
                (stopBuf ?: mutableListOf<HistoryPoint>().also { stopBuf = it }).add(p)
            } else {
                flushStop()
                if (!filters.stopsOnly) {
                    val prevPoint = prev
                    // "Same local day" is the only case where a
                    // distance / duration line for this Move makes
                    // sense. Otherwise we'd be showing the gap from
                    // last night's report to this morning's first
                    // fix as if it were a continuous trip — which
                    // produced lines like "52 m · 12 h 3 min" on the
                    // chronologically-first entry of a day.
                    val sameDay = prevPoint != null &&
                        localDayStart(prevPoint.timestampMs) ==
                            localDayStart(p.timestampMs)
                    val dist = if (sameDay) {
                        haversineMeters(prevPoint!!.latitude, prevPoint.longitude,
                            p.latitude, p.longitude)
                    } else 0.0
                    val dur = if (sameDay) {
                        p.timestampMs - prevPoint!!.timestampMs
                    } else 0L
                    out += HistoryEntry.Move(
                        id = "move-${p.id}",
                        timestampMs = p.timestampMs,
                        point = p,
                        fromPrevMeters = dist,
                        durationFromPrevMs = dur,
                    )
                }
            }
            prev = p
        }
        flushStop()
        // Newest-first to match `points`.
        return out.asReversed()
    }

    private companion object {
        const val DAY_MS: Long = 24L * 60L * 60L * 1000L
        const val STOP_RADIUS_M: Double = 25.0
        const val STOP_MIN_MS: Long = 5L * 60L * 1000L

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * kotlin.math.PI / 180.0
            val dLon = (lon2 - lon1) * kotlin.math.PI / 180.0
            val a = sin(dLat / 2).let { it * it } +
                cos(lat1 * kotlin.math.PI / 180.0) *
                cos(lat2 * kotlin.math.PI / 180.0) *
                sin(dLon / 2).let { it * it }
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}

data class HistoryFilters(
    val stopsOnly: Boolean = false,
    val hideLowAccuracy: Boolean = false,
    val accuracyThresholdMeters: Long = 100L,
)

data class HistoryUiState(
    val rangeStartMs: Long? = null,
    val rangeEndMs: Long? = null,
    /** Raw classified points, newest first. The UI uses this for the map. */
    val points: List<HistoryPoint> = emptyList(),
    /**
     * Grouped, filtered list for the bottom-sheet UI. Stop runs are
     * collapsed into a single expandable entry; Moves are kept as
     * individual entries with distance / duration from the previous
     * point.
     */
    val entries: List<HistoryEntry> = emptyList(),
    val filters: HistoryFilters = HistoryFilters(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

enum class HistoryPointKind { STOP, MOVE }

data class HistoryPoint(
    val id: String,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Long,
    val address: String? = null,
    val kind: HistoryPointKind = HistoryPointKind.MOVE,
)

/**
 * Entry as rendered by the bottom-sheet list. Entries are produced by
 * [HistoryViewModel.buildEntries] from the classified points + the
 * active filters; the UI never has to repeat that grouping work.
 */
sealed class HistoryEntry {
    abstract val id: String
    abstract val timestampMs: Long

    data class Stop(
        override val id: String,
        override val timestampMs: Long,
        val arrivalMs: Long,
        val departureMs: Long,
        /** Latest point in the cluster; carries the resolved address. */
        val anchor: HistoryPoint,
        /** All raw points that made up this stop, oldest first. */
        val members: List<HistoryPoint>,
    ) : HistoryEntry() {
        val dwellMs: Long get() = departureMs - arrivalMs
    }

    data class Move(
        override val id: String,
        override val timestampMs: Long,
        val point: HistoryPoint,
        /** Distance from the previous (older) point, in meters. */
        val fromPrevMeters: Double,
        /** Duration since the previous (older) point, in ms. */
        val durationFromPrevMs: Long,
    ) : HistoryEntry() {
        val avgSpeedKmh: Double
            get() = if (durationFromPrevMs > 0) {
                (fromPrevMeters / 1000.0) / (durationFromPrevMs / 3_600_000.0)
            } else 0.0
    }
}
