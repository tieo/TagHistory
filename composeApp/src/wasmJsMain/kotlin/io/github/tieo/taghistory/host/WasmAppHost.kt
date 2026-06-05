package io.github.tieo.taghistory.host

import io.github.tieo.taghistory.AppHostFactories
import io.github.tieo.taghistory.anisette.AnisetteJsProvider
import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginService
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import io.github.tieo.taghistory.apple.gsa.GsaClient
import io.github.tieo.taghistory.apple.http.HttpTransport
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
 * Browser host.
 *
 * Anisette headers are generated on-device through the bundled
 * [AnisetteJsProvider] (Unicorn-Engine WASM emulator running Apple's
 * own libCoreADI / libstoreservicescore — same identity bytes the
 * Android ottjni bridge produces, no third-party server). When the
 * anisette dist files are not deployed, sign-in surfaces a clear
 * setup message instead of leaking the user's machine ID anywhere.
 */
class WasmAppHost(
    private val db: TagHistoryDatabase,
    private val settingsFactory: SettingsFactory,
    private val crypto: SecureBlobStore,
    private val anisetteProvider: AnisetteJsProvider?,
) {
    private val httpTransport: HttpTransport = defaultPlatformHttpTransport()
    private val anisette = anisetteProvider?.let { AnisetteClient(it) }

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
        val service = anisette?.let { ani ->
            AppleLoginService(
                account = account,
                http = httpTransport,
                anisette = ani,
                gsa = GsaClient(httpTransport, ani),
                mobileMe = MobileMeClient(httpTransport, ani),
            )
        }
        return AppleLoginViewModel(
            startLogin = { email, password ->
                if (service == null) {
                    throw IllegalStateException(
                        "Anisette bridge not installed. Run scripts/build-web-anisette.sh " +
                            "to vendor lbr77/anisette-js + extract the Apple libs before signing in.",
                    )
                }
                service.login(email, password)
            },
            onLoggedIn = {
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
            val reportsClient = anisette?.let { LocationReportsClient(httpTransport, it) }
            MapViewModel(
                beaconRepo = beaconRepo,
                userDataRepo = userDataRepo,
                authRepo = userAuthRepo,
                fetchReports = { beaconsById, hoursBack ->
                    if (reportsClient == null || anisette == null) return@MapViewModel emptyMap()
                    val auth = userAuthRepo.getUserAuth() ?: return@MapViewModel emptyMap()
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
        onRefreshNow = { "Refresh not wired on web yet" },
        reverseGeocode = null,
        onShareGpx = null,
        onExportTags = null,
    )
}

private fun openInNewTab(url: String) {
    js("window.open(url, '_blank')")
}

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
