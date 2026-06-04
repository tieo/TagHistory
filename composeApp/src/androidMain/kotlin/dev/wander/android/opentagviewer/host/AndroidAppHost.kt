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
        val reportsClient = LocationReportsClient(http, anisette)

        // Cache the decrypted account across cascade rungs (rung 1→6→24 all fire
        // within seconds). Keystore decrypt can take 100–300 ms per call; without
        // caching the cascade pays that cost 3×. TTL of 2 min covers the cascade
        // while still refreshing auth on the next periodic 60 s tick.
        var cachedAccount: AppleAccount? = null
        var cachedAccountExpiryMs: Long = 0L

        return MapViewModel(
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
                AppleReportsService(reportsClient, account)
                    .fetchLastReportsByBeacon(accessories, hoursBack)
            },
            reverseGeocode = { lat, lon -> reverseGeocode(lat, lon) },
            currentLocation = { lastKnownDeviceLocation() },
        )
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
     * Build the "Refresh now" callback used by Settings. Fetches recent
     * reports for every owned beacon via [AppleReportsService] and stores
     * them. Returns a user-facing status line: a success summary on OK,
     * `"Sign in first"` when auth is missing, or an error sentence on
     * failure. Reuses the lambda shape used by MapViewModel so the two
     * refresh paths share the same network/auth logic.
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
            val reports = AppleReportsService(LocationReportsClient(http, anisette), account)
                .fetchLastReportsByBeacon(accessories, hoursBack = 24 * 7)
            if (reports.isNotEmpty()) beaconRepo.storeToLocationCache(reports)
            val total = reports.values.sumOf { it.size }
            "Refreshed ${reports.size} beacons • $total reports"
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
        onRefreshNow: (suspend () -> String?)? = createRefreshNowCallback(),
    ): AppHostFactories = AppHostFactories(
        createLogin = { createLoginViewModel() },
        createMap = { createMapViewModelOrNull() },
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
        onRefreshNow = onRefreshNow,
        onShareGpx = { title, dayLabel, points ->
            shareDayAsGpx(context, title, dayLabel, points)
        },
    )

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
            val result = runCatching {
                BeaconSyncOrchestrator.DefaultAccessoryLoader(owned!!)
            }
            val accessory = result.getOrNull()
            if (accessory == null) {
                parseFailed++
                val e = result.exceptionOrNull()
                Log.w(TAG, "beacon=$id skipped: ${e?.javaClass?.simpleName}: ${e?.message}")
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
            val db = TagHistoryDatabase(DatabaseDriverFactory(app).create())
            val settings = SettingsFactory(app)
            val crypto = SecureBlobStore()
            return AndroidAppHost(app, http, anisette, db, settings, crypto)
        }
    }
}
