package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.anisette.AnisetteProvider
import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginService
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import io.github.tieo.taghistory.apple.gsa.GsaClient
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.mobileme.MobileMeClient
import io.github.tieo.taghistory.apple.reports.AppleReportsService
import io.github.tieo.taghistory.apple.reports.LocationReportsClient
import io.github.tieo.taghistory.data.importer.AppleExportParser
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.UserSettings
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.GeocodeCacheRepository
import io.github.tieo.taghistory.data.repo.SyncOutcome
import io.github.tieo.taghistory.data.repo.SyncTrigger
import io.github.tieo.taghistory.data.repo.SyncRunRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.UserBeaconOptions
import io.github.tieo.taghistory.sync.BeaconSyncOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * The server's state and behavior, independent of HTTP: one database, one
 * Apple session, one sync loop. It reuses the app's own sync orchestrator,
 * Apple client and repositories, so a report stored here is byte for byte
 * the row the phone would have stored.
 *
 * All database access goes through [io] because the JDBC driver blocks.
 */
class TagHistoryServer(
    private val db: TagHistoryDatabase,
    private val settings: com.russhwolf.settings.Settings,
    blobStore: SecureBlobStore,
    anisetteProvider: AnisetteProvider,
    private val http: HttpTransport,
    private val syncIntervalMinutes: Int,
    private val geocoder: Geocoder?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(TagHistoryServer::class.java)
    private val io = Dispatchers.IO

    val beaconRepo = BeaconRepository(db, nowMs)
    val syncRunRepo = SyncRunRepository(db, nowMs)
    private val geocodeCache = GeocodeCacheRepository(db, nowMs)
    private val authRepo = UserAuthRepository(settings, blobStore, BLOB_ALIAS)
    private val settingsRepo = UserSettingsRepository(settings).also {
        // The server has no settings screen: the interval comes from its
        // configuration and sync is always on.
        it.storeUserSettings(
            UserSettings(backgroundSyncEnabled = true, backgroundSyncIntervalMinutes = syncIntervalMinutes),
        )
    }
    private val anisette = AnisetteClient(anisetteProvider)

    /** Fixed per database; see [ServerStatus.instanceId]. */
    val instanceId: String = settings.getStringOrNull(KEY_INSTANCE)
        ?: UUID.randomUUID().toString().also { settings.putString(KEY_INSTANCE, it) }

    val login = LoginCoordinator(
        signIn = { account, email, password ->
            AppleLoginService(
                account = account,
                http = http,
                anisette = anisette,
                gsa = GsaClient(http, anisette),
                mobileMe = MobileMeClient(http, anisette),
            ).login(email, password)
        },
        onLoggedIn = { account ->
            withContext(io) { authRepo.storeUserAuth(account.exportToJson().encodeToByteArray()) }
            log.info("Apple sign-in finished, starting a sync")
            launchSync(SyncTrigger.MANUAL)
        },
    )

    // Parsed accessories keyed by beacon id and plist content. Reusing an
    // instance keeps its derived-key memo, so an hourly sync does not redo
    // the key derivation for every index of every beacon.
    private val accessories = ConcurrentHashMap<String, Pair<Int, FindMyAccessory>>()

    private fun accessoryFor(owned: OwnedBeacons): FindMyAccessory? {
        val content = owned.content ?: return null
        val hash = content.hashCode()
        accessories[owned.id]?.let { (h, acc) -> if (h == hash) return acc }
        val acc = FindMyAccessory.fromPlist(content.encodeToByteArray())
        accessories[owned.id] = hash to acc
        return acc
    }

    private val orchestrator = BeaconSyncOrchestrator(
        settingsRepo = settingsRepo,
        authRepo = authRepo,
        beaconRepo = beaconRepo,
        fetchReports = { account: AppleAccount, accessoriesById, hoursBack ->
            AppleReportsService(LocationReportsClient(http, anisette), account)
                .fetchLastReportsByBeacon(accessoriesById, hoursBack)
        },
        accessoryLoader = ::accessoryFor,
        maxHoursBack = BeaconSyncOrchestrator.DEFAULT_MAX_HOURS_BACK,
        syncRunRepo = syncRunRepo,
        nowMs = nowMs,
    )

    // One sync at a time: the scheduled loop and a client's "sync now" must
    // not sweep Apple twice in parallel.
    private val syncLock = Mutex()
    private lateinit var scope: CoroutineScope

    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch {
            while (isActive) {
                val run = runCatching { runSync(SyncTrigger.WORKER) }
                    .onFailure { log.error("Scheduled sync crashed", it) }
                    .getOrNull()
                // A transient failure (network, Apple hiccup) retries sooner
                // than the interval, so one bad minute does not leave a gap
                // of a whole interval in the history.
                val retry = run == null || run.outcome == SyncOutcome.RETRY.name
                val waitMinutes = if (retry) minOf(RETRY_MINUTES, syncIntervalMinutes) else syncIntervalMinutes
                delay(waitMinutes * 60_000L)
            }
        }
    }

    private fun launchSync(trigger: SyncTrigger) {
        if (::scope.isInitialized) scope.launch { runSync(trigger) }
    }

    suspend fun runSync(trigger: SyncTrigger): SyncRunDto = syncLock.withLock {
        val outcome = withContext(io) { orchestrator.run(trigger) }
        log.info("Sync ($trigger): $outcome")
        withContext(io) { latestRun() } ?: error("sync run was not recorded")
    }

    suspend fun status(): ServerStatus = withContext(io) {
        ServerStatus(
            instanceId = instanceId,
            signedIn = authRepo.getUserAuth() != null,
            beaconCount = beaconRepo.getAllBeacons().size,
            reportCount = db.locationReportQueries.countAll().executeAsOne(),
            lastRun = latestRun(),
            syncIntervalMinutes = syncIntervalMinutes,
            serverTimeMs = nowMs(),
        )
    }

    suspend fun logout() = withContext(io) { authRepo.clearUser() }

    /** Imports an OpenTagViewer export zip. Returns how many beacons it held. */
    suspend fun import(zip: ByteArray): Int {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = input.readBytes()
            }
        }
        return when (val parsed = AppleExportParser.parse(entries, nowMs())) {
            is AppleExportParser.ParseResult.Err -> throw IllegalArgumentException(parsed.message)
            is AppleExportParser.ParseResult.Ok -> {
                withContext(io) { beaconRepo.addNewImport(parsed.data) }
                launchSync(SyncTrigger.MANUAL)
                parsed.imported
            }
        }
    }

    suspend fun beacons(): BeaconsSnapshot = withContext(io) {
        val live = beaconRepo.getAllBeacons()
        val importIds = live.mapNotNull { it.ownedBeaconInfo?.import_id }.toSet()
        BeaconsSnapshot(
            imports = importIds.mapNotNull { beaconRepo.getImportById(it) }.map {
                ImportDto(it.id, it.version, it.imported_at, it.exported_at, it.source_user, it.via)
            },
            owned = live.mapNotNull { it.ownedBeaconInfo }.map {
                OwnedBeaconDto(it.id, it.import_id, KeyRedaction.redactOwnedBeacon(it.content), it.version)
            },
            naming = live.mapNotNull { it.beaconNamingRecord }.map {
                NamingRecordDto(it.id, it.import_id, it.version, it.content)
            },
            options = live.mapNotNull { it.userBeaconOptions }.map {
                BeaconOptionsDto(it.beacon_id, it.last_update, it.ui_name, it.ui_emoji)
            },
        )
    }

    suspend fun setBeaconOptions(options: BeaconOptionsDto) = withContext(io) {
        require(beaconRepo.getById(options.beaconId) != null) { "Unknown beacon ${options.beaconId}" }
        beaconRepo.storeUserBeaconOptions(
            UserBeaconOptions(options.beaconId, nowMs(), options.uiName, options.uiEmoji),
        )
    }

    suspend fun removeBeacon(beaconId: String) = withContext(io) {
        require(beaconRepo.getById(beaconId) != null) { "Unknown beacon $beaconId" }
        beaconRepo.markBeaconAsRemoved(beaconId)
    }

    suspend fun reports(after: Long, limit: Int): ReportsPage = withContext(io) {
        val size = limit.coerceIn(1, ServerApi.MAX_REPORTS_PAGE)
        // One extra row tells whether another page follows.
        val rows = db.locationReportQueries.replicationPage(after, size.toLong() + 1).executeAsList()
        val page = rows.take(size).map {
            ReportDto(
                seq = it.seq,
                hashId = it.hash_id,
                beaconId = it.beacon_id,
                publishedAt = it.published_at,
                description = it.description,
                timestamp = it.timestamp,
                confidence = it.confidence,
                latitude = it.latitude,
                longitude = it.longitude,
                horizontalAccuracy = it.horizontal_accuracy,
                status = it.status,
                lastUpdate = it.last_update,
            )
        }
        ReportsPage(reports = page, next = page.lastOrNull()?.seq ?: after, hasMore = rows.size > size)
    }

    /**
     * Stores reports a client fetched itself and returns how many were new.
     * They hash like the server's own fetches, so a report both sides fetched
     * is stored once; reports for beacons the server does not know are dropped.
     */
    suspend fun uploadReports(uploads: List<ReportUpload>): Int = withContext(io) {
        val known = beaconRepo.getAllBeacons().map { it.beaconId }.toSet()
        val byBeacon = uploads.filter { it.beaconId in known }.groupBy(
            keySelector = { it.beaconId },
            valueTransform = { upload ->
                BeaconLocationReport(
                    publishedAt = upload.publishedAt,
                    description = upload.description.orEmpty(),
                    timestamp = upload.timestamp,
                    confidence = upload.confidence,
                    latitude = upload.latitude,
                    longitude = upload.longitude,
                    horizontalAccuracy = upload.horizontalAccuracy,
                    status = upload.status,
                )
            },
        )
        beaconRepo.storeUploadedReports(byBeacon)
    }

    suspend fun syncRuns(after: Long): List<SyncRunDto> = withContext(io) {
        db.syncRunRecordQueries.since(after, SYNC_RUNS_PAGE).executeAsList().map { it.toDto() }
    }

    suspend fun geocode(latitude: Double, longitude: Double): String? {
        withContext(io) { geocodeCache.get(latitude, longitude) }?.let { return it }
        val resolved = geocoder?.reverse(latitude, longitude) ?: return null
        withContext(io) { geocodeCache.put(latitude, longitude, resolved) }
        return resolved
    }

    private fun latestRun(): SyncRunDto? =
        db.syncRunRecordQueries.recent(1).executeAsOneOrNull()?.toDto()

    private fun io.github.tieo.taghistory.db.SyncRunRecord.toDto() = SyncRunDto(
        id = id,
        startedAt = started_at,
        trigger = trigger_kind,
        outcome = outcome,
        detail = detail,
        persistedReports = persisted_reports,
        beaconCount = beacon_count,
        windowHours = window_hours,
        durationMs = duration_ms,
    )

    private companion object {
        const val BLOB_ALIAS = "apple_account_key"
        const val KEY_INSTANCE = "instance_id"
        const val SYNC_RUNS_PAGE = 500L
        const val RETRY_MINUTES = 5
    }
}
