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
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * KMP ViewModel for the per-beacon location history. Owns:
 *   - DB read of cached reports for a time range,
 *   - background network fetch + persist,
 *   - persistent reverse-geocoding with sync DB cache hits + parallel
 *     resolve of the rest, deduped by rounded coordinate so two reports
 *     at the same place share one Geocoder call,
 *   - stop / move classification (consecutive points within ~25 m and
 *     >5 minutes count as a "stop"),
 *   - per-day summary (total distance, time on the move, stop count).
 *
 * The platform host injects:
 *   - `fetchRange` for HTTPS calls (kept out of common code),
 *   - a thin `realReverseGeocode` lambda that wraps the system Geocoder.
 *     The VM only calls it on cache miss.
 */
@OptIn(ExperimentalTime::class)
class HistoryViewModel(
    private val beaconRepo: BeaconRepository,
    private val beaconId: String,
    /** Optional: fetch additional reports for a date range. */
    private val fetchRange: suspend (String, Long, Long) -> List<BeaconLocationReport> =
        { _, _, _ -> emptyList() },
    /**
     * On-cache-miss reverse geocode. Returns null if the platform doesn't
     * support geocoding or the lookup failed. Cached results are read
     * directly from [geocodeCache] so this lambda only runs for misses.
     */
    private val realReverseGeocode: (suspend (Double, Double) -> String?)? = null,
    private val geocodeCache: GeocodeCacheRepository? = null,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val geocodeConcurrency: Int = 16,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope

    fun load(startUnixMs: Long, endUnixMs: Long) {
        PerfTrace.mark("vm.load() called")
        _state.update { it.copy(rangeStartMs = startUnixMs, rangeEndMs = endUnixMs) }
        runScope.launch {
            emitPoints()
            PerfTrace.mark("vm.load() emitted")
            kickoffGeocoding()
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
                // Hop off Main for the actual fetch. fetchRange is the
                // platform-injected lambda that drives AppleReportsService:
                // HTTPS calls + AES-GCM decryption of every report. None
                // of those are suspending boundaries on their own, so
                // without an explicit dispatcher hop they run on
                // viewModelScope = Main.immediate and freeze the UI for
                // the entire fetch + decrypt window (multiple seconds on
                // a real device with a populated cache).
                val fetched = withContext(ioDispatcher) {
                    fetchRange(beaconId, startUnixMs, endUnixMs)
                }
                PerfTrace.mark("network fetch done (${fetched.size})")
                val newRowsAdded = fetched.isNotEmpty()
                if (newRowsAdded) {
                    withContext(ioDispatcher) {
                        beaconRepo.storeToLocationCache(mapOf(beaconId to fetched))
                    }
                    PerfTrace.mark("storeToLocationCache done")
                }
                // Always emit at least once so callers that only invoke
                // fetchAndLoad (without a prior load) see whatever was
                // already cached in the DB.
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

    private suspend fun emitPoints() {
        val start = _state.value.rangeStartMs ?: return
        val end = _state.value.rangeEndMs ?: return
        val points = withContext(ioDispatcher) {
            PerfTrace.mark("emitPoints DB read start")
            val rows = beaconRepo.getLocationsFor(beaconId, start, end)
            PerfTrace.mark("emitPoints DB rows=${rows.size}")
            val sorted = rows.sortedBy { it.timestamp }
            // Pre-fill addresses from the persistent geocode cache. Each
            // hit is a single SQLite point query — fast enough to do
            // synchronously even for hundreds of points, and avoids the
            // Geocoder round-trip entirely for repeat visits.
            val cache = geocodeCache
            val cached: Map<String, String> = if (cache != null) {
                cache.getMany(sorted.map { it.latitude to it.longitude })
            } else emptyMap()
            val mapped = sorted.map { it.toUi(cached, cache) }
            // Newest-first for the list, then classify with stop/move.
            val classified = classify(mapped.sortedByDescending { it.timestampMs })
            PerfTrace.mark("emitPoints mapped + classified n=${classified.size}")
            classified
        }
        _state.update { it.copy(points = points) }
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
            // kind defaults to MOVE; classify() promotes long-dwell points
            // to STOP after the full list is built.
            kind = HistoryPointKind.MOVE,
        )
    }

    /**
     * Walks the (newest-first) list and labels runs of points within
     * STOP_RADIUS_M of one another and spanning at least STOP_MIN_MS as
     * stops. Anything else is a movement waypoint. The classification is
     * purely UI sugar — points and IDs are unchanged.
     */
    private fun classify(points: List<HistoryPoint>): List<HistoryPoint> {
        if (points.size < 2) return points.map { it.copy(kind = HistoryPointKind.STOP) }
        val out = ArrayList<HistoryPoint>(points.size)
        // Iterate chronologically (oldest first) so dwell math is intuitive.
        val chrono = points.asReversed()
        var clusterStartIdx = 0
        var i = 1
        while (i <= chrono.size) {
            val end = if (i == chrono.size) i else i
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
            // Group points by rounded key so two points at the same
            // place don't both call the Geocoder. This is the single
            // biggest win for any "spent 4 hours at home" history day.
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
                            // Patch every point in the group to the
                            // resolved address, in one state update so
                            // the list doesn't flicker per row.
                            val ids = group.mapTo(HashSet(group.size)) { it.id }
                            _state.update { state ->
                                state.copy(
                                    points = state.points.map { p ->
                                        if (p.id in ids) p.copy(address = resolved) else p
                                    },
                                )
                            }
                        }
                    }
                }
            }
            PerfTrace.mark("kickoffGeocoding all groups done")
        }
    }

    private companion object {
        const val DAY_MS: Long = 24L * 60L * 60L * 1000L

        /** Two points within this distance count as the same location. */
        const val STOP_RADIUS_M: Double = 25.0

        /** Minimum dwell time for a cluster to be promoted to a STOP. */
        const val STOP_MIN_MS: Long = 5L * 60L * 1000L

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * kotlin.math.PI / 180.0
            val dLon = (lon2 - lon1) * kotlin.math.PI / 180.0
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(lat1 * kotlin.math.PI / 180.0) *
                kotlin.math.cos(lat2 * kotlin.math.PI / 180.0) *
                kotlin.math.sin(dLon / 2).let { it * it }
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            return r * c
        }
    }
}

data class HistoryUiState(
    val rangeStartMs: Long? = null,
    val rangeEndMs: Long? = null,
    val points: List<HistoryPoint> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

enum class HistoryPointKind { STOP, MOVE }

data class HistoryPoint(
    /**
     * Stable unique identifier for this point. Sourced from the DB row
     * primary key (SHA-256 hash) when available; the UI uses this as the
     * Compose `key` for LazyColumn items so duplicate timestamps cannot
     * crash the screen.
     */
    val id: String,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Long,
    /**
     * Resolved street-level address, if any. Populated synchronously
     * from the persistent geocode cache when the points list is built,
     * and patched in place later as background lookups resolve.
     */
    val address: String? = null,
    /**
     * STOP for points inside a long-dwell cluster, MOVE otherwise. Used
     * by the list to render the timeline rail icon and by the map to
     * pick which points get a labeled bubble.
     */
    val kind: HistoryPointKind = HistoryPointKind.MOVE,
)
