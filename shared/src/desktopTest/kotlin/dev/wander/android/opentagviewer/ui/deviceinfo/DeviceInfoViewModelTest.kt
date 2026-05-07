package io.github.tieo.taghistory.ui.deviceinfo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.TagHistoryDatabase
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInfoViewModelTest {

    private lateinit var db: TagHistoryDatabase
    private lateinit var beaconRepo: BeaconRepository

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        db = TagHistoryDatabase(driver)
        beaconRepo = BeaconRepository(db) { 1_000L }
    }

    private fun seedBeacon(id: String, name: String?, emoji: String?) {
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
            latitude = lat, longitude = lon, horizontalAccuracy = 7,
            status = 0, lastUpdate = ts,
        )
    }

    @Test
    fun `load populates beacon metadata and last location`() = runTest {
        seedBeacon("b1", "Keys", "🔑")
        seedLocation("b1", lat = 1.5, lon = 2.5, ts = 300L)
        seedLocation("b1", lat = 3.5, lon = 4.5, ts = 400L)
        val vm = DeviceInfoViewModel(beaconRepo, "b1", nowMs = { 1L }, scope = this)
        vm.load()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Keys", s.displayName)
        assertEquals("🔑", s.emoji)
        val loc = s.lastLocation
        assertNotNull(loc)
        assertEquals(400L, loc.timestamp)
        assertEquals(3.5, loc.latitude)
    }

    @Test
    fun `load flips notFound when beacon missing`() = runTest {
        val vm = DeviceInfoViewModel(beaconRepo, "missing", scope = this)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.notFound)
    }

    @Test
    fun `rename persists through repository`() = runTest {
        seedBeacon("b1", "Old", null)
        val vm = DeviceInfoViewModel(beaconRepo, "b1", nowMs = { 100L }, scope = this)
        vm.rename("Fresh", "🧸")
        advanceUntilIdle()
        assertEquals("Fresh", vm.state.value.displayName)
        assertEquals("🧸", vm.state.value.emoji)
    }

    @Test
    fun `remove flips removed flag`() = runTest {
        seedBeacon("b1", "Keys", null)
        val vm = DeviceInfoViewModel(beaconRepo, "b1", scope = this)
        vm.remove()
        assertTrue(vm.state.value.removed)
    }
}
