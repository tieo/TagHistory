package io.github.tieo.taghistory.host

import io.github.tieo.taghistory.AppHostFactories
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.SyncRunRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.data.storage.SettingsFactory
import io.github.tieo.taghistory.data.storage.SettingsStoreNames
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.server.ServerBeaconEditor
import io.github.tieo.taghistory.server.ServerClient
import io.github.tieo.taghistory.server.ServerLogin
import io.github.tieo.taghistory.server.ServerReplicator
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoViewModel
import io.github.tieo.taghistory.ui.history.HistoryViewModel
import io.github.tieo.taghistory.ui.login.AppleLoginViewModel
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.nearby.NearbyViewModel
import io.github.tieo.taghistory.ui.settings.SettingsViewModel
import kotlinx.browser.window

/**
 * Browser host. The web app is a client of the TagHistory sync server it is
 * served from: the server holds the Apple session and the full history, and
 * this host replicates the server's tables into the browser's own database
 * (sql.js persisted to IndexedDB). Every screen and view model is the
 * standalone app's, reading local rows; only the edges differ:
 *
 *  - "Fetching reports" is a pull from the server, which already fetched
 *    them from Apple.
 *  - Sign-in relays the user's Apple ID and second factor to the server.
 *  - Renames, removals, imports and sign-out are server calls.
 */
class WasmAppHost(
    private val db: TagHistoryDatabase,
    private val settingsFactory: SettingsFactory,
    private val client: ServerClient,
    /** Whether the server held an Apple session when the page loaded. */
    serverSignedIn: Boolean,
) {
    private var signedIn = serverSignedIn

    private val beaconRepo by lazy { BeaconRepository(db) }
    private val syncRunRepo by lazy { SyncRunRepository(db) }
    private val userSettingsRepo by lazy {
        UserSettingsRepository(settingsFactory.create(SettingsStoreNames.USER_SETTINGS))
    }
    private val userDataRepo by lazy {
        UserDataRepository(settingsFactory.create(SettingsStoreNames.USER_CACHE))
    }

    // Unused for the session (the server holds it) but required by the
    // shared view models' constructors.
    private val userAuthRepo by lazy {
        UserAuthRepository(settingsFactory.create(SettingsStoreNames.USER_AUTH), SecureBlobStore(), "unused")
    }

    val replicator by lazy {
        ServerReplicator(
            client = client,
            db = db,
            beaconRepo = beaconRepo,
            cursors = settingsFactory.create(REPLICATION_STORE),
            nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        )
    }

    private val editor by lazy { ServerBeaconEditor(client, beaconRepo) }

    fun buildFactories(appVersion: String): AppHostFactories = AppHostFactories(
        createLogin = {
            val login = ServerLogin(client)
            AppleLoginViewModel(
                startLogin = { email, password -> login.login(email, password) },
                onLoggedIn = {
                    signedIn = true
                    replicator.pull()
                },
            )
        },
        createMap = {
            MapViewModel(
                beaconRepo = beaconRepo,
                userDataRepo = userDataRepo,
                authRepo = userAuthRepo,
                // The server already fetched from Apple; refreshing here means
                // copying whatever it stored since the last pull. The rows land
                // in the local tables, and the map observes those.
                fetchReports = { _, _ ->
                    replicator.pull()
                    emptyMap()
                },
                reverseGeocode = { lat, lon -> runCatching { client.geocode(lat, lon) }.getOrNull() },
                // A pull is one cheap request when nothing is new, so the
                // app's Apple rate limit does not apply.
                minRefreshIntervalMs = 0L,
                editor = editor,
                isSignedIn = { signedIn },
            )
        },
        isLoggedIn = { signedIn },
        createSettings = {
            SettingsViewModel(
                settingsRepo = userSettingsRepo,
                authRepo = userAuthRepo,
                signOutAction = {
                    client.logout()
                    signedIn = false
                },
            )
        },
        createDeviceInfo = { beaconId -> DeviceInfoViewModel(beaconRepo, beaconId, editor = editor) },
        createHistory = { beaconId -> HistoryViewModel(beaconRepo = beaconRepo, beaconId = beaconId) },
        createNearby = { null as NearbyViewModel? },
        appVersion = appVersion,
        openUrl = { url -> openInNewTab(url) },
        routeTo = { lat, lon, _ ->
            openInNewTab("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon")
        },
        settingsFlow = userSettingsRepo.flow,
        syncRuns = { syncRunRepo.observeRecent() },
        onImport = { importFromFile() },
        onRefreshNow = {
            val run = client.syncNow()
            replicator.pull()
            if (run.outcome == "SUCCESS") {
                "Refreshed ${run.beaconCount} beacons • ${run.persistedReports} reports"
            } else {
                run.detail ?: run.outcome
            }
        },
        reverseGeocode = { lat, lon -> runCatching { client.geocode(lat, lon) }.getOrNull() },
        onShareGpx = null,
        onExportTags = null,
    )

    /** Lets the user pick an OpenTagViewer export zip and uploads it. */
    private suspend fun importFromFile(): String? {
        val bytes = pickFile(".zip") ?: return null
        val result = client.import(bytes)
        replicator.pull()
        return "Imported ${result.imported} beacon${if (result.imported == 1) "" else "s"}"
    }

    private companion object {
        const val REPLICATION_STORE = "server_replication"
    }
}

private fun openInNewTab(url: String) {
    window.open(url, "_blank")
}
