package io.github.tieo.taghistory.web.host

import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.SyncRun
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
import io.github.tieo.taghistory.server.ServerReplicator
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoViewModel
import io.github.tieo.taghistory.ui.history.HistoryViewModel
import io.github.tieo.taghistory.ui.history.localDayStart
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.settings.SettingsViewModel
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow

/**
 * The web app's services. The page is a client of the TagHistory sync server
 * it is served from: the server holds the Apple session and the full history,
 * and this host replicates the server's tables into the browser's own
 * database (sql.js persisted to IndexedDB). The view models are the Android
 * app's, reading local rows; only the edges differ:
 *
 *  - "Fetching reports" is a pull from the server, which fetched them from Apple.
 *  - Renames, removals, imports and sign out are server calls.
 */
class WebHost(
    private val db: TagHistoryDatabase,
    private val settingsFactory: SettingsFactory,
    val client: ServerClient,
) {
    private val beaconRepo by lazy { BeaconRepository(db) }
    private val syncRunRepo by lazy { SyncRunRepository(db) }
    val userSettingsRepo by lazy {
        UserSettingsRepository(settingsFactory.create(SettingsStoreNames.USER_SETTINGS))
    }
    private val userDataRepo by lazy {
        UserDataRepository(settingsFactory.create(SettingsStoreNames.USER_CACHE))
    }

    // The server holds the session; the shared view models still take an
    // auth repository, which stays empty here.
    private val userAuthRepo by lazy {
        UserAuthRepository(settingsFactory.create(SettingsStoreNames.USER_AUTH), SecureBlobStore(), "unused")
    }

    val replicator by lazy {
        ServerReplicator(
            client = client,
            db = db,
            beaconRepo = beaconRepo,
            cursors = settingsFactory.create(REPLICATION_STORE),
            nowMs = { nowMs() },
        )
    }

    private val editor by lazy { ServerBeaconEditor(client, beaconRepo) }

    /** One map view model for the whole page, so the tag list survives navigation. */
    val map: MapViewModel by lazy {
        MapViewModel(
            beaconRepo = beaconRepo,
            userDataRepo = userDataRepo,
            authRepo = userAuthRepo,
            // The server already fetched from Apple; refreshing here copies
            // what it stored since the last pull into the local tables, which
            // the map observes.
            fetchReports = { _, _ ->
                replicator.pull()
                emptyMap()
            },
            reverseGeocode = { lat, lon -> runCatching { client.geocode(lat, lon) }.getOrNull() },
            // A pull is one cheap request when nothing is new, so the app's
            // Apple rate limit does not apply.
            minRefreshIntervalMs = 0L,
            editor = editor,
            isSignedIn = { true },
        ).also { it.boot() }
    }

    fun history(beaconId: String) = HistoryViewModel(
        beaconRepo = beaconRepo,
        beaconId = beaconId,
        realReverseGeocode = { lat, lon -> runCatching { client.geocode(lat, lon) }.getOrNull() },
        localDayStart = ::localDayStart,
    )

    fun deviceInfo(beaconId: String) = DeviceInfoViewModel(beaconRepo, beaconId, editor = editor)

    fun settings() = SettingsViewModel(
        settingsRepo = userSettingsRepo,
        authRepo = userAuthRepo,
        signOutAction = { client.logout() },
    )

    fun syncRuns(): Flow<List<SyncRun>> = syncRunRepo.observeRecent()

    /** Every emoji the tags show. */
    fun emojiInUse(): String =
        beaconRepo.getAllBeaconInformation().values.mapNotNull { it.displayEmoji }.toSet().joinToString("")

    /** Asks the server to sync with Apple now, then copies what it stored. */
    suspend fun syncNow(): String {
        val run = client.syncNow()
        replicator.pull()
        map.refreshNames()
        return if (run.outcome == "SUCCESS") {
            "Synced ${run.beaconCount} tags, ${run.persistedReports} reports"
        } else {
            run.detail ?: run.outcome
        }
    }

    /** Lets the user pick an OpenTagViewer export zip and uploads it; null when cancelled. */
    suspend fun importFromFile(): String? {
        val bytes = pickFile(".zip") ?: return null
        val result = client.import(bytes)
        replicator.pull()
        map.reboot()
        return "Imported ${result.imported} tag${if (result.imported == 1) "" else "s"}"
    }

    private companion object {
        const val REPLICATION_STORE = "server_replication"
    }
}

fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** The Apple sign-in page served next to the app; see `login.html`. */
fun openLoginPage() {
    window.location.replace("/login.html")
}
