package io.github.tieo.taghistory.ui.map

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.UserBeaconOptions
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Drives [MapViewModel] with an in-memory SQLDelight DB + a stub
 * [UserAuthRepository] that has auth stored. Refresh fetches are provided
 * as a lambda — no HTTP, no anisette, no keystore. Covers the states the
 * map screen actually renders against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private lateinit var db: TagHistoryDatabase
    private lateinit var beaconRepo: BeaconRepository
    private lateinit var userDataRepo: UserDataRepository
    private lateinit var authRepo: UserAuthRepository

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        db = TagHistoryDatabase(driver)
        beaconRepo = BeaconRepository(db) { 1_000L }
        userDataRepo = UserDataRepository(PropertiesSettings(Properties()))
        // Desktop SecureBlobStore is a passthrough — storing a valid
        // UserAuthData JSON is enough for getUserAuth() to succeed.
        authRepo = UserAuthRepository(
            settings = PropertiesSettings(Properties()),
            crypto = SecureBlobStore(),
            keystoreAlias = "test",
        )
        authRepo.storeUserAuth("""{"account":null}""".encodeToByteArray())
    }

    private fun seedBeacon(id: String, name: String, emoji: String?) {
        db.beaconNamingRecordQueries.upsert(
            id = id, importId = null, version = "1", content = null, isRemoved = false,
        )
        db.ownedBeaconQueries.upsert(
            id = id, importId = null, content = "plist", version = "1", isRemoved = false,
        )
        db.userBeaconOptionsQueries.upsert(
            beaconId = id, lastUpdate = 0L, uiName = name, uiEmoji = emoji,
        )
    }

    private fun seedLocation(id: String, lat: Double, lon: Double, ts: Long) {
        db.locationReportQueries.upsert(
            hashId = "hash-$id-$ts", beaconId = id, publishedAt = ts,
            description = "", timestamp = ts, confidence = 1,
            latitude = lat, longitude = lon, horizontalAccuracy = 10,
            status = 0, lastUpdate = ts,
        )
    }

    private fun TestScope.buildVm(
        fetchReports: suspend (Map<String, io.github.tieo.taghistory.data.model.BeaconData>, Int) -> Map<String, List<BeaconLocationReport>> =
            { _, _ -> emptyMap() },
        reverseGeocode: suspend (Double, Double) -> String? = { _, _ -> null },
    ): MapViewModel = MapViewModel(
        beaconRepo = beaconRepo,
        userDataRepo = userDataRepo,
        authRepo = authRepo,
        fetchReports = fetchReports,
        reverseGeocode = reverseGeocode,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `boot without auth flips requireLogin`() = runTest {
        authRepo = UserAuthRepository(PropertiesSettings(Properties()), SecureBlobStore(), "test")
        val vm = buildVm()
        vm.boot()
        advanceUntilIdle()
        assertTrue(vm.state.value.requireLogin)
    }

    @Test
    fun `boot loads cached beacons and their latest location`() = runTest {
        seedBeacon("b1", "Keys", "🔑")
        seedLocation("b1", lat = 1.0, lon = 2.0, ts = 100L)
        seedLocation("b1", lat = 3.0, lon = 4.0, ts = 200L) // newer wins
        val vm = buildVm()
        vm.boot()
        advanceUntilIdle()
        val markers = vm.state.value.markers
        assertEquals(1, markers.size)
        assertEquals("Keys", markers[0].displayName)
        assertEquals("🔑", markers[0].emoji)
        assertEquals(3.0, markers[0].latitude)
        assertEquals(200L, markers[0].lastUpdatedMs)
    }

    @Test
    fun `refresh persists new reports and updates marker position`() = runTest {
        seedBeacon("b1", "Keys", null)
        val reports = mapOf(
            "b1" to listOf(
                BeaconLocationReport(
                    publishedAt = 500L, description = "", timestamp = 500L,
                    confidence = 1, latitude = 42.0, longitude = -73.0,
                    horizontalAccuracy = 10, status = 0,
                )
            )
        )
        val vm = buildVm(fetchReports = { _, _ -> reports })
        vm.boot()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.isRefreshing)
        assertTrue(vm.state.value.isInitialFetchComplete)
        assertEquals(42.0, vm.state.value.markers.single().latitude)
        // And the DB now has the report cached:
        val stored = beaconRepo.getLastLocationsForAll()
        assertEquals(42.0, stored["b1"]?.latitude)
    }

    @Test
    fun `refresh failure surfaces error without clobbering cached markers`() = runTest {
        seedBeacon("b1", "Keys", null)
        seedLocation("b1", lat = 1.0, lon = 2.0, ts = 100L)
        val vm = buildVm(fetchReports = { _, _ -> throw RuntimeException("network down") })
        vm.boot()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals("network down", vm.state.value.refreshError)
        assertFalse(vm.state.value.isRefreshing)
        assertEquals(1, vm.state.value.markers.size) // cached marker kept
    }

    @Test
    fun `concurrent refresh calls do not double-fetch`() = runTest {
        seedBeacon("b1", "Keys", null)
        var fetchCount = 0
        val vm = buildVm(
            fetchReports = { _, _ ->
                fetchCount++
                emptyMap()
            },
        )
        vm.boot()
        advanceUntilIdle()
        // Reset after init's own boot+refresh cascade so the counter below
        // only measures the two explicit refresh() calls.
        fetchCount = 0
        vm.refresh()
        vm.refresh() // should be a no-op — isRefreshing is set synchronously
        advanceUntilIdle()
        assertEquals(1, fetchCount)
    }

    @Test
    fun `saveCamera persists the position`() = runTest {
        val vm = buildVm()
        vm.saveCamera(UserMapCameraPosition(zoom = 14f, lat = 1.1, lon = 2.2))
        advanceUntilIdle()
        val restored = userDataRepo.getLastCameraPosition()
        assertNotNull(restored)
        assertEquals(14f, restored.zoom)
    }

    @Test
    fun `boot restores last camera position`() = runTest {
        userDataRepo.storeLastCameraPosition(UserMapCameraPosition(zoom = 10f, lat = 5.0, lon = 6.0))
        val vm = buildVm()
        vm.boot()
        advanceUntilIdle()
        assertEquals(10f, vm.state.value.initialCamera?.zoom)
    }

    @Test
    fun `selectBeacon updates the selected id`() = runTest {
        val vm = buildVm()
        vm.selectBeacon("b1")
        assertEquals("b1", vm.state.value.selectedBeaconId)
        vm.selectBeacon(null)
        assertNull(vm.state.value.selectedBeaconId)
    }

    @Test
    fun `geocoding backfills addressLine per marker`() = runTest {
        seedBeacon("b1", "Keys", null)
        val vm = buildVm(
            fetchReports = { _, _ ->
                mapOf(
                    "b1" to listOf(
                        BeaconLocationReport(
                            publishedAt = 1L, description = "", timestamp = 1L,
                            confidence = 1, latitude = 1.0, longitude = 2.0,
                            horizontalAccuracy = 10, status = 0,
                        )
                    )
                )
            },
            reverseGeocode = { _, _ -> "123 Main St" },
        )
        vm.boot()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals("123 Main St", vm.state.value.markers.single().addressLine)
    }

}
