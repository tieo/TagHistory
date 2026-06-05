package io.github.tieo.taghistory.host

import io.github.tieo.taghistory.AppHostFactories
import io.github.tieo.taghistory.anisette.RemoteAnisetteProvider
import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginService
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import io.github.tieo.taghistory.apple.gsa.GsaClient
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.http.createPlatformHttpClient
import io.github.tieo.taghistory.apple.http.defaultPlatformHttpTransport
import io.github.tieo.taghistory.apple.mobileme.MobileMeClient
import io.github.tieo.taghistory.apple.reports.AppleReportsService
import io.github.tieo.taghistory.apple.reports.LocationReportsClient
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.data.storage.SettingsFactory
import io.github.tieo.taghistory.data.storage.SettingsStoreNames
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoViewModel
import io.github.tieo.taghistory.ui.history.HistoryViewModel
import io.github.tieo.taghistory.ui.login.AppleLoginViewModel
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.nearby.NearbyViewModel
import io.github.tieo.taghistory.ui.settings.SettingsViewModel

/**
 * Browser host. The Android host wires real Apple-login, BLE,
 * geocoding, share-intents and a system map; web has none of
 * those. Everything below is either a real shared-code object
 * (DB, repos, settings) or a stub that surfaces "not on web" via
 * an empty/throwing fallback.
 */
class WasmAppHost(
    private val db: TagHistoryDatabase,
    private val settingsFactory: SettingsFactory,
    private val crypto: SecureBlobStore,
    private val anisetteUrl: String = "https://ani.sidestore.io",
) {
    private val rawHttpClient = createPlatformHttpClient()
    private val httpTransport: HttpTransport = defaultPlatformHttpTransport()
    private val anisetteProvider = RemoteAnisetteProvider(rawHttpClient, anisetteUrl)
    private val anisette = AnisetteClient(anisetteProvider)

    private val beaconRepo by lazy { BeaconRepository(db) }
    private val userSettingsRepo by lazy {
        UserSettingsRepository(settingsFactory.create(SettingsStoreNames.USER_SETTINGS))
    }
    private val userDataRepo by lazy {
        UserDataRepository(settingsFactory.create(SettingsStoreNames.USER_CACHE))
    }
    private val userAuthRepo by lazy {
        UserAuthRepository(
            settingsFactory.create(SettingsStoreNames.USER_AUTH),
            crypto,
            "apple_account_key",
        )
    }

    private fun createLoginViewModel(onLoggedIn: suspend () -> Unit): AppleLoginViewModel {
        val account = AppleAccount()
        val service = AppleLoginService(
            account = account,
            http = httpTransport,
            anisette = anisette,
            gsa = GsaClient(httpTransport, anisette),
            mobileMe = MobileMeClient(httpTransport, anisette),
        )
        return AppleLoginViewModel(
            startLogin = { email, password -> service.login(email, password) },
            onLoggedIn = {
                // Persist the freshly-authenticated account so the next
                // boot lands on MapScreen instead of LoginScreen.
                runCatching {
                    val json = account.exportToJson()
                    val envelope = crypto.encrypt(json.encodeToByteArray(), "apple_account_key")
                    userAuthRepo.storeUserAuth(envelope)
                }
                onLoggedIn()
            },
        )
    }

    fun buildFactories(appVersion: String): AppHostFactories = AppHostFactories(
        createLogin = { createLoginViewModel(onLoggedIn = {}) },
        createMap = {
            val reportsClient = LocationReportsClient(httpTransport, anisette)
            MapViewModel(
                beaconRepo = beaconRepo,
                userDataRepo = userDataRepo,
                authRepo = userAuthRepo,
                fetchReports = { beaconsById, hoursBack ->
                    val auth = userAuthRepo.getUserAuth()
                        ?: return@MapViewModel emptyMap()
                    val plain = userAuthRepo.decrypt(auth.data).decodeToString()
                    val account = AppleAccount.restoreFromJson(plain)
                    val accessories = loadAccessoriesQuiet(
                        beaconsById.mapValues { it.value.ownedBeaconInfo },
                    )
                    if (accessories.isEmpty()) return@MapViewModel emptyMap()
                    AppleReportsService(reportsClient, account)
                        .fetchLastReportsByBeacon(accessories, hoursBack)
                },
                refreshIntervalMs = 0L,
            )
        },
        createSettings = { SettingsViewModel(userSettingsRepo, userAuthRepo) },
        createDeviceInfo = { beaconId -> DeviceInfoViewModel(beaconRepo, beaconId) },
        createHistory = { beaconId ->
            HistoryViewModel(beaconRepo = beaconRepo, beaconId = beaconId)
        },
        createNearby = { null as NearbyViewModel? },
        appVersion = appVersion,
        openUrl = { url -> openInNewTab(url) },
        routeTo = { lat, lon, _ ->
            openInNewTab("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon")
        },
        settingsFlow = userSettingsRepo.flow,
        onImport = null,
        onRefreshNow = { "Web preview — refresh not wired" },
        reverseGeocode = null,
        onShareGpx = null,
        onExportTags = null,
    )
}

private fun openInNewTab(url: String) {
    js("window.open(url, '_blank')")
}

/**
 * Web-side accessory loader. Same shape as the Android host's
 * `loadAccessoriesVerbose` but quieter — there is no Logcat on web
 * to dump per-beacon parse failures into; the empty map result is
 * surfaced through the regular MapViewModel "no reports" path.
 */
private fun loadAccessoriesQuiet(
    input: Map<String, io.github.tieo.taghistory.db.OwnedBeacons?>,
): Map<String, FindMyAccessory> = buildMap {
    for ((id, owned) in input) {
        val content = owned?.content ?: continue
        runCatching { FindMyAccessory.fromPlist(content.encodeToByteArray()) }
            .getOrNull()
            ?.let { put(id, it) }
    }
}
