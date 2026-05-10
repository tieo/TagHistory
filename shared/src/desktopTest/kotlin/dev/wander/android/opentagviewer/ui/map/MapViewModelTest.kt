package io.github.tieo.taghistory.ui.map

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.TagHistoryDatabase
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

    @Test
    fun `refresh promotes selection from unlocated default to most recently located`() = runTest {
        // Fresh-import scenario: 3 beacons in DB, none cached with a location.
        // boot() defaults selection to the first card it sees (could be any).
        // After refresh brings real positions in, selection should auto-promote
        // to the most-recently-located beacon, not stay on an unlocated one.
        seedBeacon("a", "Auto", null)
        seedBeacon("b", "Bike", null)
        seedBeacon("c", "Cat", null)
        val reports = mapOf(
            "b" to listOf(BeaconLocationReport(
                publishedAt = 100L, description = "", timestamp = 100L,
                confidence = 1, latitude = 10.0, longitude = 20.0,
                horizontalAccuracy = 5, status = 0,
            )),
            "c" to listOf(BeaconLocationReport(
                publishedAt = 500L, description = "", timestamp = 500L,
                confidence = 1, latitude = 50.0, longitude = 60.0,
                horizontalAccuracy = 5, status = 0,
            )),
        )
        val vm = buildVm(fetchReports = { _, _ -> reports })
        vm.boot()
        advanceUntilIdle()
        // Boot picked SOMETHING — possibly the unlocated 'a' (or any of them).
        val bootSelection = vm.state.value.selectedBeaconId
        assertNotNull(bootSelection)

        vm.refresh()
        advanceUntilIdle()

        // 'c' has the newest report (ts=500), so it must end up selected.
        assertEquals("c", vm.state.value.selectedBeaconId,
            "After refresh, selection must promote to most-recently-located beacon")
    }

    @Test
    fun `refresh keeps selection if user already chose a located beacon`() = runTest {
        seedBeacon("a", "Auto", null)
        seedBeacon("b", "Bike", null)
        seedLocation("a", lat = 1.0, lon = 2.0, ts = 100L)
        val reports = mapOf(
            "b" to listOf(BeaconLocationReport(
                publishedAt = 999L, description = "", timestamp = 999L,
                confidence = 1, latitude = 9.0, longitude = 9.0,
                horizontalAccuracy = 5, status = 0,
            )),
        )
        val vm = buildVm(fetchReports = { _, _ -> reports })
        vm.boot()
        advanceUntilIdle()
        // User explicitly selects 'a' (the older but already-located beacon).
        vm.selectBeacon("a")
        vm.refresh()
        advanceUntilIdle()
        // Refresh brings 'b' with a newer ts but user's explicit pick stays.
        assertEquals("a", vm.state.value.selectedBeaconId,
            "Explicit user selection on a located beacon must not be overridden")
    }

    @Test
    fun `five consecutive refreshes do not reorder selection`() = runTest {
        // Periodic-tick storm: every refresh brings the same located beacons
        // back in different orderings. Selection must stay where the user
        // (or first auto-promote) put it.
        seedBeacon("a", "Auto", null)
        seedBeacon("b", "Bike", null)
        seedBeacon("c", "Cat", null)
        var tick = 0
        val vm = buildVm(fetchReports = { _, _ ->
            tick++
            // Each tick the freshest changes. b on tick 1, c on 2, a on 3, b on 4, c on 5.
            val freshIds = listOf("b", "c", "a", "b", "c")
            mapOf(
                freshIds[(tick - 1) % freshIds.size] to listOf(BeaconLocationReport(
                    publishedAt = (1000L * tick), description = "", timestamp = (1000L * tick),
                    confidence = 1, latitude = 1.0, longitude = 1.0,
                    horizontalAccuracy = 5, status = 0,
                )),
            )
        })
        vm.boot(); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        val firstSelection = vm.state.value.selectedBeaconId
        assertNotNull(firstSelection)

        repeat(4) { vm.refresh(); advanceUntilIdle() }

        assertEquals(firstSelection, vm.state.value.selectedBeaconId,
            "Selection must not drift across periodic refreshes")
    }

    @Test
    fun `refresh while a previous refresh is in flight does not double-mutate selection`() = runTest {
        // A real Settings "Refresh now" tap can land mid-cycle. The second
        // refresh's pickSelection must NOT promote selection a second time
        // even though there's a running cascade and a freshest-located
        // candidate in the latest reports.
        seedBeacon("a", "Auto", null)
        seedBeacon("b", "Bike", null)
        var call = 0
        val vm = buildVm(fetchReports = { _, _ ->
            call++
            when (call) {
                1 -> mapOf("a" to listOf(BeaconLocationReport(
                    publishedAt = 100L, description = "", timestamp = 100L,
                    confidence = 1, latitude = 1.0, longitude = 1.0,
                    horizontalAccuracy = 5, status = 0,
                )))
                else -> mapOf("b" to listOf(BeaconLocationReport(
                    publishedAt = 9999L, description = "", timestamp = 9999L,
                    confidence = 1, latitude = 2.0, longitude = 2.0,
                    horizontalAccuracy = 5, status = 0,
                )))
            }
        })
        vm.boot(); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertEquals("a", vm.state.value.selectedBeaconId)

        // Two more triggered close together.
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()

        assertEquals("a", vm.state.value.selectedBeaconId,
            "Subsequent refreshes (even back-to-back) must not switch selection")
    }

    @Test
    fun `reboot resets one-shot guard so post-import refresh auto-promotes again`() = runTest {
        // Import flow: user imports a fresh beacon set, mapVm.reboot() is
        // called. The auto-promote flag must be cleared so the first refresh
        // after reboot can pick the freshest located beacon (otherwise the
        // map sits on whatever default boot picked, possibly unlocated).
        seedBeacon("a", "Auto", null)
        var call = 0
        val vm = buildVm(fetchReports = { _, _ ->
            call++
            mapOf("a" to listOf(BeaconLocationReport(
                publishedAt = 100L * call, description = "", timestamp = 100L * call,
                confidence = 1, latitude = 1.0, longitude = 1.0,
                horizontalAccuracy = 5, status = 0,
            )))
        })
        vm.boot(); advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertEquals("a", vm.state.value.selectedBeaconId)

        // Simulate an import: new beacons land, reboot wipes the VM caches.
        seedBeacon("z", "Zebra", null)
        vm.reboot()
        advanceUntilIdle()

        assertEquals("a", vm.state.value.selectedBeaconId,
            "Post-reboot first refresh must still promote to a located beacon")
    }

    @Test
    fun `cards always sort by lastUpdatedMs descending`() = runTest {
        seedBeacon("a", "Auto", null)
        seedBeacon("b", "Bike", null)
        seedBeacon("c", "Cat", null)

        var call = 0
        val vm = buildVm(fetchReports = { _, _ ->
            call++
            if (call == 1) mapOf("a" to listOf(BeaconLocationReport(
                publishedAt = 100L, description = "", timestamp = 100L,
                confidence = 1, latitude = 1.0, longitude = 1.0,
                horizontalAccuracy = 5, status = 0,
            )))
            else mapOf(
                "a" to listOf(BeaconLocationReport(
                    publishedAt = 100L, description = "", timestamp = 100L,
                    confidence = 1, latitude = 1.0, longitude = 1.0,
                    horizontalAccuracy = 5, status = 0,
                )),
                "b" to listOf(BeaconLocationReport(
                    publishedAt = 99999L, description = "", timestamp = 99999L,
                    confidence = 1, latitude = 2.0, longitude = 2.0,
                    horizontalAccuracy = 5, status = 0,
                )),
            )
        })
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.state.value.cards.map { it.beaconId },
            "After init's first refresh: only 'a' located, so 'a' first then unlocated tail")

        vm.refresh(); advanceUntilIdle()
        assertEquals(listOf("b", "a", "c"), vm.state.value.cards.map { it.beaconId },
            "After 'b' gets a newer report: 'b' moves to the front")
    }

    @Test
    fun `auto-promote is one-shot — second refresh must not switch to a newer beacon`() = runTest {
        // Periodic refresh case: boot promoted us to beacon 'a' on the first
        // refresh because its report came back. On the next periodic tick,
        // beacon 'b' replies with an even newer report. That MUST NOT yank
        // the selection over to 'b' — that's the "card switching by itself"
        // bug the user kept seeing on their phone.
        seedBeacon("a", "Auto", null)
        seedBeacon("b", "Bike", null)

        var call = 0
        val vm = buildVm(fetchReports = { _, _ ->
            call++
            when (call) {
                1 -> mapOf("a" to listOf(BeaconLocationReport(
                    publishedAt = 100L, description = "", timestamp = 100L,
                    confidence = 1, latitude = 1.0, longitude = 1.0,
                    horizontalAccuracy = 5, status = 0,
                )))
                else -> mapOf(
                    "a" to listOf(BeaconLocationReport(
                        publishedAt = 100L, description = "", timestamp = 100L,
                        confidence = 1, latitude = 1.0, longitude = 1.0,
                        horizontalAccuracy = 5, status = 0,
                    )),
                    "b" to listOf(BeaconLocationReport(
                        publishedAt = 9999L, description = "", timestamp = 9999L,
                        confidence = 1, latitude = 2.0, longitude = 2.0,
                        horizontalAccuracy = 5, status = 0,
                    )),
                )
            }
        })
        vm.boot()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals("a", vm.state.value.selectedBeaconId,
            "First refresh auto-promotes to 'a' since it's the only located one")

        vm.refresh()
        advanceUntilIdle()
        assertEquals("a", vm.state.value.selectedBeaconId,
            "Second refresh must NOT auto-promote to 'b' — auto-promote is one-shot")
    }

}
