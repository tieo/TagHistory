package io.github.tieo.taghistory.host

import io.github.tieo.taghistory.AppHostFactories
import io.github.tieo.taghistory.apple.account.LoginResult
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
                        "Web preview only — Apple login needs an Anisette backend " +
                            "the wasm target does not ship yet.",
                    )
                },
                onLoggedIn = {},
            )
        },
        createMap = {
            // Returning a real MapViewModel even without auth keeps
            // App's root nav on MapScreen; MapViewModel itself flips
            // requireLogin = true on boot when there's no auth blob,
            // which routes back to LoginScreen — same end state as
            // the Android host but with a working DB in between.
            MapViewModel(
                beaconRepo = beaconRepo,
                userDataRepo = userDataRepo,
                authRepo = userAuthRepo,
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
        onRefreshNow = { "Web preview — refresh not wired" },
        reverseGeocode = null,
        onShareGpx = null,
        onExportTags = null,
    )
}

private fun openInNewTab(url: String) {
    js("window.open(url, '_blank')")
}
