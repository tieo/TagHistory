package io.github.tieo.taghistory.host

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import io.github.tieo.taghistory.AppHostFactories
import io.github.tieo.taghistory.anisette.NativeAnisetteProvider
import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginService
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.gsa.GsaClient
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.http.defaultPlatformHttpTransport
import io.github.tieo.taghistory.apple.mobileme.MobileMeClient
import io.github.tieo.taghistory.apple.reports.AppleReportsService
import io.github.tieo.taghistory.apple.reports.LocationReportsClient
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.GeocodeCacheRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.data.storage.SettingsFactory
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.sync.BeaconSyncOrchestrator
import io.github.tieo.taghistory.sync.BeaconSyncWorker
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoViewModel
import io.github.tieo.taghistory.ui.history.HistoryViewModel
import io.github.tieo.taghistory.ui.login.AppleLoginViewModel
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.settings.SettingsViewModel
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires the Compose MP screens to the real Android stack: OkHttp
 * transport (Apple Root CA trust-anchored), on-device anisette via the
 * Rust ottjni bridge, GSA + MobileMe clients, SQLDelight DB, the
 * AndroidKeystore-backed auth store, and SharedPreferences-backed
 * settings.
 *
 * A single [AndroidAppHost] instance owns the long-lived services
 * (database, http transport, anisette) so reloading a screen after
 * logout/login doesn't leak connections.
 */
@OptIn(ExperimentalTime::class)
class AndroidAppHost private constructor(
    private val context: Context,
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val db: TagHistoryDatabase,
    private val settingsFactory: SettingsFactory,
    private val crypto: SecureBlobStore,
) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

    private val geocodeCacheRepo by lazy { GeocodeCacheRepository(db) }

    /**
     * Raw Geocoder call with no cache. The on-disk cache is layered on
     * top of this in [reverseGeocodeWithCache] (used by the map screen)
     * and inside HistoryViewModel (which deduplicates by rounded key
     * before calling here).
     */
    private suspend fun rawReverseGeocode(lat: Double, lon: Double): String? {
        val gc = geocoder ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                gc.getFromLocation(lat, lon, 1)?.firstOrNull()?.getAddressLine(0)
            }.getOrNull()
        }
    }

    /**
     * Cache-aware reverse geocode for the map screen. Cache keyed by
     * ~1 m rounded coordinates, so repeat visits to the same place
     * skip the Geocoder entirely.
     */
    private suspend fun reverseGeocodeWithCache(lat: Double, lon: Double): String? {
        geocodeCacheRepo.get(lat, lon)?.let { return it }
        val resolved = rawReverseGeocode(lat, lon) ?: return null
        runCatching { geocodeCacheRepo.put(lat, lon, resolved) }
        return resolved
    }

    /** Back-compat alias for older callers. */
    private suspend fun reverseGeocode(lat: Double, lon: Double): String? =
        reverseGeocodeWithCache(lat, lon)

    // Cache of parsed FindMyAccessory instances keyed by beaconId, with the
    // plist content's hash so a re-import (changed content) rebuilds. Without
    // this, every fetch re-parsed the plist AND threw away the accessory's
    // per-index key cache (AccessoryKeyGenerator.keyAt memoization), forcing
    // a full SHA-256/EC key re-derivation on every refresh. Reusing the
    // instance keeps that memoization alive across refreshes.
    private val accessoryCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Int, io.github.tieo.taghistory.apple.findmy.FindMyAccessory>>()

    private fun cachedAccessory(
        id: String,
        owned: io.github.tieo.taghistory.db.OwnedBeacons,
    ): io.github.tieo.taghistory.apple.findmy.FindMyAccessory? {
        val content = owned.content ?: return null
        val hash = content.hashCode()
        accessoryCache[id]?.let { (h, acc) -> if (h == hash) return acc }
        val acc = runCatching {
            io.github.tieo.taghistory.apple.findmy.FindMyAccessory.fromPlist(content.encodeToByteArray())
        }.getOrNull() ?: return null
        accessoryCache[id] = hash to acc
        return acc
    }

    private val beaconRepo by lazy { BeaconRepository(db) }
    private val userSettingsRepo by lazy {
        io.github.tieo.taghistory.data.repo.UserSettingsRepository(
            settingsFactory.create(SETTINGS_STORE_USER_SETTINGS),
        )
    }
    private val userDataRepo by lazy {
        UserDataRepository(settingsFactory.create(SETTINGS_STORE_USER_DATA))
    }
    private val userAuthRepo by lazy {
        UserAuthRepository(
            settingsFactory.create(SETTINGS_STORE_USER_AUTH),
            crypto,
            KEYSTORE_ALIAS_APPLE_ACCOUNT,
        )
    }

    fun createLoginViewModel(onLoggedIn: suspend () -> Unit = {}): AppleLoginViewModel {
        val account = AppleAccount()
        val service = AppleLoginService(
            account = account,
            http = http,
            anisette = anisette,
            gsa = GsaClient(http, anisette),
            mobileMe = MobileMeClient(http, anisette),
        )
        return AppleLoginViewModel(
            startLogin = { email, password -> service.login(email, password) },
            onLoggedIn = {
                // Persist the successful login blob so MapViewModel can
                // rehydrate the account on the next screen.
                userAuthRepo.storeUserAuth(account.exportToJson().encodeToByteArray())
                onLoggedIn()
            },
        )
    }

    /**
     * Produce a MapViewModel if the user is signed in, null otherwise.
     * Returning null is the signal to [App] that it should show login
     * instead of the map.
     */
    fun createMapViewModelOrNull(): MapViewModel? {
        if (userAuthRepo.getUserAuth() == null) return null

        // Cache the decrypted account across cascade rungs (rung 1→6→24 all fire
        // within seconds). Keystore decrypt can take 100–300 ms per call; without
        // caching the cascade pays that cost 3×. TTL of 2 min covers the cascade
        // while still refreshing auth on the next periodic 60 s tick.
        var cachedAccount: AppleAccount? = null
        var cachedAccountExpiryMs: Long = 0L

        val vm = MapViewModel(
            beaconRepo = beaconRepo,
            userDataRepo = userDataRepo,
            authRepo = userAuthRepo,
            fetchReports = { beaconsById, hoursBack ->
                val now = System.currentTimeMillis()
                val account = if (cachedAccount != null && now < cachedAccountExpiryMs) {
                    cachedAccount!!
                } else {
                    val auth = userAuthRepo.getUserAuth()
                        ?: return@MapViewModel emptyMap()
                    val plain = userAuthRepo.decrypt(auth.data).decodeToString()
                    AppleAccount.restoreFromJson(plain).also {
                        cachedAccount = it
                        cachedAccountExpiryMs = now + 120_000L
                    }
                }
                val accessories = loadAccessoriesVerbose(beaconsById.mapValues { it.value.ownedBeaconInfo })
                if (accessories.isEmpty()) return@MapViewModel emptyMap()
                // Same fetch seam as refresh-now + the background worker.
                appleReportsFetcher.fetch(account, accessories, hoursBack)
            },
            reverseGeocode = { lat, lon -> reverseGeocode(lat, lon) },
            currentLocation = { lastKnownDeviceLocation() },
        )
        // Expose the live UI state for `adb shell dumpsys activity provider
        // io.github.tieo.taghistory/.DebugDumpProvider`. Lambda is evaluated
        // at dump time so it always reads the current StateFlow value.
        DebugStateRegistry.register("map") {
            val s = vm.state.value
            buildString {
                appendLine("  isRefreshing=${s.isRefreshing}")
                appendLine("  isInitialFetchComplete=${s.isInitialFetchComplete}")
                appendLine("  requireLogin=${s.requireLogin}")
                appendLine("  refreshError=${s.refreshError}")
                appendLine("  fetchingBeaconIds=${s.fetchingBeaconIds.size} ${s.fetchingBeaconIds}")
                appendLine("  markers=${s.markers.size} cards=${s.cards.size}")
                appendLine("  selectedBeaconId=${s.selectedBeaconId}")
                append("  markerSummary=" + s.markers.joinToString(";") {
                    "${it.beaconId.take(8)}@${it.latitude},${it.longitude}+${it.lastUpdatedMs}"
                })
            }
        }
        return vm
    }

    /**
     * Last-known device location from any provider — used to sort tag
     * cards by distance-to-me. Null when permission is missing or no fix
     * has been cached; viewmodel falls back to recency sort in that case.
     */
    private fun lastKnownDeviceLocation(): Pair<Double, Double>? {
        val granted = context.checkSelfPermission(
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        // Pick the freshest fix across all providers — no active listener,
        // just cached last-known. Passive fallback covers phones that only
        // report via network or GPS intermittently.
        val best = lm.allProviders.mapNotNull { provider ->
            runCatching {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(provider)
            }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        return best.latitude to best.longitude
    }

    fun createSettingsViewModel(): SettingsViewModel =
        SettingsViewModel(userSettingsRepo, userAuthRepo)

    fun createDeviceInfoViewModel(beaconId: String): DeviceInfoViewModel =
        DeviceInfoViewModel(beaconRepo, beaconId)

    /**
     * Wraps a platform-level zip-picker callback into the shape expected
     * by [AppHostFactories.onImport]. The caller owns the launcher —
     * [pickZip] should return the picked [android.net.Uri] (or null on
     * cancel) from whatever file-picker flow it has set up.
     */
    fun createImportCallback(pickZip: suspend () -> android.net.Uri?): suspend () -> String? = label@{
        Log.i(TAG, "import callback: calling pickZip()")
        val uri = pickZip()
        if (uri == null) {
            Log.i(TAG, "import callback: pickZip returned null (user cancelled)")
            return@label null
        }
        Log.i(TAG, "import callback: got uri=$uri, running import")
        val result = runAppleExportImport(context, uri, beaconRepo)
        Log.i(TAG, "import callback: result='$result'")
        result
    }

    /**
     * Two-stage variant for the Manage-Tags screen: picks a zip,
     * parses it, returns an [io.github.tieo.taghistory.ImportPreview]
     * the UI can render in a checklist dialog. The actual DB write
     * happens via [createImportCommitCallback] once the user confirms.
     */
    fun createImportPreviewCallback(
        pickZip: suspend () -> android.net.Uri?,
    ): suspend () -> io.github.tieo.taghistory.ImportPreview? = label@{
        Log.i(TAG, "import preview: calling pickZip()")
        val uri = pickZip()
        if (uri == null) {
            Log.i(TAG, "import preview: cancelled")
            return@label io.github.tieo.taghistory.ImportPreview.Cancelled
        }
        Log.i(TAG, "import preview: got uri=$uri, staging")
        stageAppleExportImport(context, uri, beaconRepo)
    }

    fun createImportCommitCallback(): suspend (
        io.github.tieo.taghistory.data.importer.AppleExportParser.Staged,
        Set<String>,
    ) -> String = { staged, ids ->
        commitAppleExportImport(staged, ids, beaconRepo)
    }

    /**
     * Single Apple-reports fetch seam, shared by every code path that
     * pulls fixes (Settings refresh-now, the background sync worker, and
     * — via [AppHostFactories.onRefreshNow] — anything else). Previously
     * each path constructed its own [AppleReportsService] + decrypt +
     * accessory-load sequence; centralising it here is the one place
     * network/auth logic lives.
     */
    private val appleReportsFetcher = BeaconSyncOrchestrator.ReportsFetcher { account, accessories, hoursBack ->
        AppleReportsService(LocationReportsClient(http, anisette), account)
            .fetchLastReportsByBeacon(accessories, hoursBack)
    }

    /**
     * The headless sync pass. Wired into both the WorkManager worker
     * (background) and used to source [createRefreshNowCallback] so the
     * fetch+store path is not duplicated. The worker checks
     * `settings.backgroundSyncEnabled` itself.
     */
    fun createSyncOrchestrator(): BeaconSyncOrchestrator = BeaconSyncOrchestrator(
        settingsRepo = userSettingsRepo,
        authRepo = userAuthRepo,
        beaconRepo = beaconRepo,
        fetchReports = appleReportsFetcher,
        // Adaptive window (see BeaconSyncOrchestrator): normally tiny (~2h) so
        // an hourly run is cheap and doesn't starve a concurrent manual reload;
        // auto-widens up to this 7-day cap only after real downtime / a fresh
        // install. The old fixed 7-day-every-hour sweep flooded network/CPU
        // (a 2h manual reload measured 9.3s while a 168h sweep ran alongside).
        maxHoursBack = 24 * 7,
    )

    /**
     * Build the "Refresh now" callback used by Settings. Forces a fetch
     * regardless of the background-sync setting (the orchestrator's
     * own `backgroundSyncEnabled` gate is for the periodic worker, not a
     * manual press), reusing [appleReportsFetcher] so there is a single
     * fetch implementation.
     */
    fun createRefreshNowCallback(): suspend () -> String? = label@{
        // Crypto + network must NOT run on Main. The Settings screen launches
        // this from a Compose-bound scope which uses Dispatchers.Main; without
        // the explicit IO hop, fetchLastReportsByBeacon ANRs the app.
        withContext(Dispatchers.IO) {
            val auth = userAuthRepo.getUserAuth() ?: return@withContext "Sign in first"
            val plain = userAuthRepo.decrypt(auth.data).decodeToString()
            val account = AppleAccount.restoreFromJson(plain)
            val beacons = beaconRepo.getAllBeaconInformation().keys
                .mapNotNull { beaconRepo.getById(it) }
            val accessories = loadAccessoriesVerbose(
                beacons.associate { it.beaconId to it.ownedBeaconInfo },
            )
            if (accessories.isEmpty()) return@withContext "No beacons to refresh"
            val reports = appleReportsFetcher.fetch(account, accessories, hoursBack = 24 * 7)
            if (reports.isNotEmpty()) beaconRepo.storeToLocationCache(reports)
            val total = reports.values.sumOf { it.size }
            "Refreshed ${reports.size} beacons • $total reports"
        }
    }

    /**
     * Install the background-sync worker. Sets the static
     * [BeaconSyncWorker.orchestratorProvider] so the WorkManager-created
     * worker can reach back into this host's DI graph, schedules /
     * cancels the periodic job per the current settings, and keeps it
     * in sync with future settings changes by collecting the settings
     * StateFlow on [scope]. Idempotent — WorkManager's UPDATE policy
     * dedupes repeat applies, and re-creating the host (activity
     * recreation) just re-arms the same unique work.
     */
    fun startBackgroundSync(scope: kotlinx.coroutines.CoroutineScope) {
        BeaconSyncWorker.orchestratorProvider = { createSyncOrchestrator() }
        scope.launch {
            userSettingsRepo.flow
                .map { it.backgroundSyncEnabled to it.backgroundSyncIntervalMinutes }
                .distinctUntilChanged()
                .collect {
                    BeaconSyncWorker.apply(context, userSettingsRepo.getUserSettings())
                }
        }
    }

    fun createNearbyViewModel(): io.github.tieo.taghistory.ui.nearby.NearbyViewModel? {
        val beacons = beaconRepo.getAllBeaconInformation().keys
            .mapNotNull { beaconRepo.getById(it) }
        if (beacons.isEmpty()) return null
        val accessories = loadAccessoriesVerbose(
            beacons.associate { it.beaconId to it.ownedBeaconInfo },
        )
        if (accessories.isEmpty()) return null
        val matcher = io.github.tieo.taghistory.nearby.NearbyMatcher(accessories)
        val scanner = io.github.tieo.taghistory.nearby.BleNearbyScanner(context, matcher)
        val uwbAvailable = io.github.tieo.taghistory.nearby.UwbCapability.isAvailable(context)
        return io.github.tieo.taghistory.ui.nearby.NearbyViewModel(
            beaconRepo = beaconRepo,
            uwbAvailable = uwbAvailable,
            loadOwnedTags = {
                val info = beaconRepo.getAllBeaconInformation()
                info.values.map {
                    io.github.tieo.taghistory.ui.nearby.OwnedTagInfo(
                        beaconId = it.beaconId,
                        displayName = it.displayName,
                        emoji = it.displayEmoji,
                    )
                }
            },
            startBleScan = { scope, onEvent ->
                scope.launch {
                    scanner.observe().collect { ev ->
                            onEvent(
                                when (ev) {
                                    is io.github.tieo.taghistory.nearby.BleNearbyScanner.Event.Hit ->
                                        io.github.tieo.taghistory.ui.nearby.NearbyScanEvent.Hit(
                                            beaconId = ev.beaconId,
                                            keyType = ev.keyType,
                                            rssi = ev.rssi,
                                        )
                                    is io.github.tieo.taghistory.nearby.BleNearbyScanner.Event.MissingPermission ->
                                        io.github.tieo.taghistory.ui.nearby.NearbyScanEvent.MissingPermission
                                    is io.github.tieo.taghistory.nearby.BleNearbyScanner.Event.BluetoothOff ->
                                        io.github.tieo.taghistory.ui.nearby.NearbyScanEvent.BluetoothOff
                                    is io.github.tieo.taghistory.nearby.BleNearbyScanner.Event.Stopped ->
                                        io.github.tieo.taghistory.ui.nearby.NearbyScanEvent.Stopped
                                }
                            )
                        }
                    }
            },
        )
    }

    fun createHistoryViewModel(beaconId: String): HistoryViewModel {
        val reportsClient = LocationReportsClient(http, anisette)
        return HistoryViewModel(
            beaconRepo = beaconRepo,
            beaconId = beaconId,
            fetchRange = { id, startMs, endMs ->
                val auth = userAuthRepo.getUserAuth() ?: return@HistoryViewModel emptyList()
                val plain = userAuthRepo.decrypt(auth.data).decodeToString()
                val account = AppleAccount.restoreFromJson(plain)
                val beacon = beaconRepo.getById(id) ?: return@HistoryViewModel emptyList()
                beacon.ownedBeaconInfo?.content ?: return@HistoryViewModel emptyList()
                val accessory = runCatching {
                    BeaconSyncOrchestrator.DefaultAccessoryLoader(beacon.ownedBeaconInfo!!)
                }.getOrNull() ?: return@HistoryViewModel emptyList()
                val from = Instant.fromEpochMilliseconds(startMs)
                val to = Instant.fromEpochMilliseconds(endMs)
                AppleReportsService(reportsClient, account)
                    .fetchReportsByBeacon(mapOf(id to accessory), from, to)[id] ?: emptyList()
            },
            // Inject the geocode pipeline pieces so HistoryViewModel can
            // do its own dedupe-by-rounded-key + parallel fan-out.
            realReverseGeocode = { lat, lon -> rawReverseGeocode(lat, lon) },
            geocodeCache = geocodeCacheRepo,
            // Tz-aware day-bucket function. Used by buildEntries to
            // detect cross-day breaks so the chronologically-first
            // Move of a day doesn't display the gap from yesterday's
            // last fix as if it were a continuous trip.
            localDayStart = { ms -> io.github.tieo.taghistory.ui.history.localDayStart(ms) },
        )
    }

    fun buildAppFactories(
        appVersion: String,
        onImport: (suspend () -> String?)? = null,
        onImportPreview: (suspend () -> io.github.tieo.taghistory.ImportPreview?)? = null,
        onRefreshNow: (suspend () -> String?)? = createRefreshNowCallback(),
    ): AppHostFactories = AppHostFactories(
        createLogin = { createLoginViewModel() },
        createMap = { createMapViewModelOrNull() },
        isLoggedIn = { userAuthRepo.getUserAuth() != null },
        createSettings = { createSettingsViewModel() },
        createDeviceInfo = { beaconId -> createDeviceInfoViewModel(beaconId) },
        createHistory = { beaconId -> createHistoryViewModel(beaconId) },
        createNearby = { createNearbyViewModel() },
        reverseGeocode = { lat, lon -> reverseGeocode(lat, lon) },
        appVersion = appVersion,
        openUrl = { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        },
        routeTo = { lat, lon, label ->
            // geo:lat,lon?q=lat,lon(label) triggers a chooser across every
            // installed nav app (Google Maps, Waze, OsmAnd, Organic Maps…).
            val encoded = Uri.encode(label)
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($encoded)")
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_VIEW, uri), "Open route in…"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        },
        settingsFlow = userSettingsRepo.flow,
        onImport = onImport,
        onImportPreview = onImportPreview,
        onImportCommit = createImportCommitCallback(),
        onRefreshNow = onRefreshNow,
        onShareGpx = { title, dayLabel, points ->
            shareDayAsGpx(context, title, dayLabel, points)
        },
        onExportTags = { beaconIds -> runExportSelected(context, beaconIds, beaconRepo) },
        isIgnoringBatteryOptimizations = { isIgnoringBatteryOptimizations() },
        requestIgnoreBatteryOptimizations = { requestIgnoreBatteryOptimizations() },
    )

    /** True when the OS is NOT battery-optimizing us (background work runs near schedule). */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val result = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        io.github.tieo.taghistory.sync.SyncLog.record(
            io.github.tieo.taghistory.sync.SyncEvent.Kind.INFO,
            "Battery optimization check",
            mapOf("ignoring" to result.toString(), "has_power_manager" to (pm != null).toString()),
        )
        return result
    }

    /**
     * Route the user to grant the battery-optimization exemption. Primary:
     * the system "Allow [app] to run in the background?" dialog (one tap).
     * Fallback: the exact battery-optimization list screen if the direct
     * action isn't resolvable on this OEM. Launched with NEW_TASK because
     * we only hold an application Context here.
     */
    private fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val useDirect = direct.resolveActivity(context.packageManager) != null
        val intent = if (useDirect) direct else fallback
        io.github.tieo.taghistory.sync.SyncLog.record(
            io.github.tieo.taghistory.sync.SyncEvent.Kind.INFO,
            "Requesting battery-optimization exemption",
            mapOf(
                "intent" to if (useDirect) "REQUEST_IGNORE (direct dialog)" else "IGNORE_SETTINGS (list fallback)",
                "currently_ignoring" to isIgnoringBatteryOptimizations().toString(),
            ),
        )
        runCatching { context.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "Could not open battery-optimization settings: ${it.message}")
                io.github.tieo.taghistory.sync.SyncLog.record(
                    io.github.tieo.taghistory.sync.SyncEvent.Kind.RUNG_FAIL,
                    "Failed to open battery-optimization screen: ${it.message}",
                )
            }
    }

    /**
     * Build FindMyAccessory for each owned beacon and surface why each one
     * failed. Previously we swallowed all loader failures with
     * `.getOrNull() ?: continue` — users saw "Not yet reported" for beacons
     * whose plist blobs were unparseable (missing `privateKey`, short
     * master-key blob, absent `pairingDate`, etc.) and had no way to tell.
     * Logging the beacon id + first line of the exception lets us triage
     * which imports are broken without shipping a debug build.
     */
    private fun loadAccessoriesVerbose(
        input: Map<String, io.github.tieo.taghistory.db.OwnedBeacons?>,
    ): Map<String, io.github.tieo.taghistory.apple.findmy.FindMyAccessory> = buildMap {
        var missingBlob = 0
        var parseFailed = 0
        for ((id, owned) in input) {
            if (owned?.content.isNullOrBlank()) {
                missingBlob++
                Log.w(TAG, "beacon=$id skipped: no OwnedBeacons.content blob on record")
                continue
            }
            val accessory = cachedAccessory(id, owned!!)
            if (accessory == null) {
                parseFailed++
                Log.w(TAG, "beacon=$id skipped: accessory parse failed")
                continue
            }
            put(id, accessory)
        }
        if (missingBlob > 0 || parseFailed > 0) {
            Log.w(
                TAG,
                "loaded ${size}/${input.size} accessories " +
                    "(skipped: missing-blob=$missingBlob, parse-failed=$parseFailed)",
            )
        }
    }

    companion object {
        private const val TAG = "OTV/Host"
        private const val SETTINGS_STORE_USER_SETTINGS = "user_settings"
        private const val SETTINGS_STORE_USER_DATA = "user_data"
        private const val SETTINGS_STORE_USER_AUTH = "user_auth"
        /** Matches the alias the Java app used so Keystore entries carry over. */
        private const val KEYSTORE_ALIAS_APPLE_ACCOUNT = "apple_account_key"

        fun create(context: Context): AndroidAppHost {
            val app = context.applicationContext
            val http = defaultPlatformHttpTransport()
            val anisette = AnisetteClient(NativeAnisetteProvider(app))
            // AndroidSqliteDriver's constructor is sync; the suspend
            // contract is for wasm's async sqljs path. Block here at
            // app-init — single-shot, off the UI thread already.
            val db = TagHistoryDatabase(
                kotlinx.coroutines.runBlocking { DatabaseDriverFactory(app).create() }
            )
            val settings = SettingsFactory(app)
            val crypto = SecureBlobStore()
            return AndroidAppHost(app, http, anisette, db, settings, crypto)
        }
    }
}
