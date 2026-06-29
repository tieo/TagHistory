package io.github.tieo.taghistory.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.ImportData
import io.github.tieo.taghistory.data.model.UserSettings
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.Import
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.OwnedBeacons
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * End-to-end orchestrator test using an in-memory SQLDelight DB + a stub
 * [BeaconSyncOrchestrator.ReportsFetcher]. Exercises the full run() shape
 * without the real network stack — per the project-standing rule
 * (Never declare fixed until full flow is tested).
 */
@OptIn(ExperimentalTime::class)
class BeaconSyncOrchestratorTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: TagHistoryDatabase
    private lateinit var beaconRepo: BeaconRepository

    private lateinit var settingsProps: Properties
    private lateinit var settingsRepo: UserSettingsRepository

    private lateinit var authProps: Properties
    private lateinit var authRepo: UserAuthRepository

    private var now: Long = 1_700_000_000_000L

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        db = TagHistoryDatabase(driver)
        beaconRepo = BeaconRepository(db) { now }

        settingsProps = Properties()
        settingsRepo = UserSettingsRepository(PropertiesSettings(settingsProps))

        authProps = Properties()
        authRepo = UserAuthRepository(
            settings = PropertiesSettings(authProps),
            crypto = SecureBlobStore(),
            keystoreAlias = "test-alias",
        )
    }

    private fun seedBeacon(id: String = "beacon-1") {
        val import = Import(
            id = 0L, version = "v1", imported_at = 1L, exported_at = 2L,
            source_user = "me@example.com", via = "unit-test",
        )
        beaconRepo.addNewImport(
            ImportData(
                anImport = import,
                ownedBeacons = listOf(
                    OwnedBeacons(
                        id = id, import_id = 0L, content = "content",
                        version = "v1", is_removed = false,
                    ),
                ),
                beaconNamingRecords = listOf(
                    BeaconNamingRecord(
                        id = id, import_id = 0L, version = "v1",
                        content = "n", is_removed = false,
                    ),
                ),
            ),
        )
    }

    private fun storeValidAuthBlob(username: String = "me@example.com") {
        val account = AppleAccount().apply { this.username = username }
        authRepo.storeUserAuth(account.exportToJson().encodeToByteArray())
    }

    private fun stubAccessory(): FindMyAccessory = FindMyAccessory(
        masterKey = ByteArray(28) { 0xAA.toByte() },
        skn = ByteArray(32) { 0xBB.toByte() },
        sks = ByteArray(32) { 0xCC.toByte() },
        pairedAt = Instant.parse("2024-06-15T09:30:00Z"),
        pairedZoneOffsetSeconds = 0,
        name = "Stub", model = "m", identifier = "id",
    )

    @Test
    fun `skips when background sync disabled`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = false))
        storeValidAuthBlob()
        seedBeacon()

        val orchestrator = BeaconSyncOrchestrator(
            settingsRepo = settingsRepo,
            authRepo = authRepo,
            beaconRepo = beaconRepo,
            fetchReports = { _, _, _ -> error("fetcher should not be invoked") },
            accessoryLoader = { stubAccessory() },
        )

        val outcome = orchestrator.run()
        val success = assertIs<BeaconSyncOrchestrator.Outcome.Success>(outcome)
        assertEquals(0, success.beaconCount)
        assertEquals(0, success.persistedReports)
    }

    @Test
    fun `skips when no stored auth`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        // no storeValidAuthBlob call
        seedBeacon()

        val orchestrator = BeaconSyncOrchestrator(
            settingsRepo, authRepo, beaconRepo,
            fetchReports = { _, _, _ -> error("fetcher should not be invoked") },
            accessoryLoader = { stubAccessory() },
        )

        val outcome = orchestrator.run()
        assertIs<BeaconSyncOrchestrator.Outcome.Success>(outcome)
    }

    @Test
    fun `skips when no beacons imported`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        storeValidAuthBlob()
        // no beacons seeded

        val orchestrator = BeaconSyncOrchestrator(
            settingsRepo, authRepo, beaconRepo,
            fetchReports = { _, _, _ -> error("fetcher should not be invoked") },
            accessoryLoader = { stubAccessory() },
        )

        val outcome = orchestrator.run()
        val success = assertIs<BeaconSyncOrchestrator.Outcome.Success>(outcome)
        assertEquals(0, success.beaconCount)
    }

    @Test
    fun `skips beacons whose accessoryLoader returns null or throws`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        storeValidAuthBlob()
        seedBeacon("good-1")
        seedBeacon("bad-null")
        seedBeacon("bad-throws")

        var fetched: Map<String, FindMyAccessory>? = null
        val orchestrator = BeaconSyncOrchestrator(
            settingsRepo, authRepo, beaconRepo,
            fetchReports = { _, accessories, _ ->
                fetched = accessories
                accessories.mapValues { (_, _) -> emptyList() }
            },
            accessoryLoader = { owned ->
                when (owned.id) {
                    "good-1" -> stubAccessory()
                    "bad-null" -> null
                    "bad-throws" -> throw IllegalStateException("bad plist")
                    else -> null
                }
            },
        )

        val outcome = orchestrator.run()
        val success = assertIs<BeaconSyncOrchestrator.Outcome.Success>(outcome)
        assertEquals(1, success.beaconCount)
        assertEquals(setOf("good-1"), fetched?.keys)
    }

    @Test
    fun `happy path fetches and persists reports`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        storeValidAuthBlob()
        seedBeacon("beacon-1")
        seedBeacon("beacon-2")

        val stubReports = mapOf(
            "beacon-1" to listOf(
                BeaconLocationReport(
                    publishedAt = 100L, description = "", timestamp = 90L,
                    confidence = 1L, latitude = 1.0, longitude = 2.0,
                    horizontalAccuracy = 5L, status = 0L,
                ),
                BeaconLocationReport(
                    publishedAt = 200L, description = "", timestamp = 180L,
                    confidence = 1L, latitude = 1.1, longitude = 2.1,
                    horizontalAccuracy = 5L, status = 0L,
                ),
            ),
            "beacon-2" to listOf(
                BeaconLocationReport(
                    publishedAt = 300L, description = "", timestamp = 280L,
                    confidence = 1L, latitude = 3.0, longitude = 4.0,
                    horizontalAccuracy = 5L, status = 0L,
                ),
            ),
        )

        var seenHoursBack: Int? = null
        var seenAccountUsername: String? = null
        val orchestrator = BeaconSyncOrchestrator(
            settingsRepo, authRepo, beaconRepo,
            fetchReports = { account, accessories, hoursBack ->
                seenAccountUsername = account.username
                seenHoursBack = hoursBack
                stubReports.filterKeys { it in accessories.keys }
            },
            accessoryLoader = { stubAccessory() },
            // Empty DB at run time (no cached fixes seeded) -> the adaptive
            // window falls back to maxHoursBack for a full backfill.
            maxHoursBack = 12,
        )

        val outcome = orchestrator.run()
        val success = assertIs<BeaconSyncOrchestrator.Outcome.Success>(outcome)
        assertEquals(2, success.beaconCount)
        assertEquals(3, success.persistedReports)
        assertEquals(12, seenHoursBack)
        assertEquals("me@example.com", seenAccountUsername)

        // Rows persisted → DB query returns them back.
        val beacon1Reports = beaconRepo.getLocationsFor("beacon-1", 0L, 10_000L)
        assertEquals(2, beacon1Reports.size)
        val beacon2Reports = beaconRepo.getLocationsFor("beacon-2", 0L, 10_000L)
        assertEquals(1, beacon2Reports.size)
        assertEquals(3.0, beacon2Reports.single().latitude)
    }

    private fun storeCachedFix(beaconId: String, timestampMs: Long) {
        beaconRepo.storeToLocationCache(
            mapOf(
                beaconId to listOf(
                    BeaconLocationReport(
                        publishedAt = timestampMs, description = "", timestamp = timestampMs,
                        confidence = 1L, latitude = 1.0, longitude = 1.0,
                        horizontalAccuracy = 5L, status = 0L,
                    ),
                ),
            ),
        )
    }

    private fun captureWindow(): Pair<BeaconSyncOrchestrator, () -> Int?> {
        var seen: Int? = null
        val orch = BeaconSyncOrchestrator(
            settingsRepo, authRepo, beaconRepo,
            fetchReports = { _, _, hoursBack -> seen = hoursBack; emptyMap() },
            accessoryLoader = { stubAccessory() },
            maxHoursBack = 24 * 7,
            minHoursBack = 2,
            nowMs = { now },
        )
        return orch to { seen }
    }

    @Test
    fun `adaptive window collapses to the floor when cached data is fresh`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        storeValidAuthBlob()
        seedBeacon("beacon-1")
        // Newest fix is 1h old -> gap(1) + margin(1) = 2, the floor.
        storeCachedFix("beacon-1", now - 3_600_000L)

        val (orch, seen) = captureWindow()
        assertIs<BeaconSyncOrchestrator.Outcome.Success>(orch.run())
        assertEquals(2, seen(), "fresh cached data -> minimum window, not a 7-day sweep")
    }

    @Test
    fun `adaptive window grows to cover a multi-day gap`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        storeValidAuthBlob()
        seedBeacon("beacon-1")
        // Newest fix is 50h old -> gap(50) + margin(1) = 51, still under the 168 cap.
        storeCachedFix("beacon-1", now - 50L * 3_600_000L)

        val (orch, seen) = captureWindow()
        assertIs<BeaconSyncOrchestrator.Outcome.Success>(orch.run())
        assertEquals(51, seen(), "a 50h gap -> ~51h window to backfill it")
    }

    @Test
    fun `fetcher exception maps to Retry outcome`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        storeValidAuthBlob()
        seedBeacon()

        val boom = RuntimeException("transient network error")
        val orchestrator = BeaconSyncOrchestrator(
            settingsRepo, authRepo, beaconRepo,
            fetchReports = { _, _, _ -> throw boom },
            accessoryLoader = { stubAccessory() },
        )

        val outcome = orchestrator.run()
        val retry = assertIs<BeaconSyncOrchestrator.Outcome.Retry>(outcome)
        assertEquals(boom, retry.cause)
    }

    @Test
    fun `rehydrates account via decrypt and JSON restore`() : Unit = runBlocking {
        settingsRepo.storeUserSettings(UserSettings(backgroundSyncEnabled = true))
        val originalAcc = AppleAccount().apply {
            username = "rehydrate@example.com"
            password = "sekret"
        }
        authRepo.storeUserAuth(originalAcc.exportToJson().encodeToByteArray())
        seedBeacon()

        var receivedUid: String? = null
        var receivedPassword: String? = null
        val orchestrator = BeaconSyncOrchestrator(
            settingsRepo, authRepo, beaconRepo,
            fetchReports = { account, _, _ ->
                receivedUid = account.uid
                receivedPassword = account.password
                emptyMap()
            },
            accessoryLoader = { stubAccessory() },
        )

        val outcome = orchestrator.run()
        assertIs<BeaconSyncOrchestrator.Outcome.Success>(outcome)
        assertEquals(originalAcc.uid, receivedUid)
        assertEquals("sekret", receivedPassword)
    }
}
