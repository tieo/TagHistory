package io.github.tieo.taghistory.host

import io.github.tieo.taghistory.AppHostFactories
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
 * Browser host. The Android host generates anisette headers on-device
 * via the Rust ottjni bridge + Apple's libCoreADI / libstoreservicescore.
 * Neither runs in a browser, and we DO NOT proxy through a third-party
 * anisette server — anisette headers identify the device (machine ID +
 * provisioning state), so sending them to anyone else leaks the user's
 * Apple identity. That means Apple login is structurally unavailable on
 * web today.
 *
 * Everything that does NOT need anisette — DB, repos, persisted
 * settings, map rendering, history view — works through the
 * commonMain code paths. Cached locations / history landed from a
 * previous Android sync are visible if the user imports them.
 */
class WasmAppHost(
    private val db: TagHistoryDatabase,
    private val settingsFactory: SettingsFactory,
    private val crypto: SecureBlobStore,
) {

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

    fun buildFactories(appVersion: String): AppHostFactories = AppHostFactories(
        createLogin = {
            AppleLoginViewModel(
                startLogin = { _, _ ->
                    throw IllegalStateException(
                        "Sign-in is not available on the web build. Anisette headers " +
                            "identify your device and the project will not proxy them " +
                            "through a third-party server. Run the Android app instead.",
                    )
                },
                onLoggedIn = {},
            )
        },
        createMap = {
            MapViewModel(
                beaconRepo = beaconRepo,
                userDataRepo = userDataRepo,
                authRepo = userAuthRepo,
                // Refresh is intentionally a no-op on web: pulling new
                // FindMy reports needs anisette + Apple GSA, which we
                // deliberately do not wire here. Pre-imported cached
                // locations still render through beaconRepo.
                fetchReports = { _, _ -> emptyMap() },
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
        onRefreshNow = { "Refresh not available on web (anisette not wired)" },
        reverseGeocode = null,
        onShareGpx = null,
        onExportTags = null,
    )
}

private fun openInNewTab(url: String) {
    js("window.open(url, '_blank')")
}
