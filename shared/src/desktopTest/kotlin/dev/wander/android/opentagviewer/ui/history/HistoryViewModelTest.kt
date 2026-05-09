package io.github.tieo.taghistory.ui.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.TagHistoryDatabase
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private lateinit var db: TagHistoryDatabase
    private lateinit var beaconRepo: BeaconRepository

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        db = TagHistoryDatabase(driver)
        beaconRepo = BeaconRepository(db) { 1_000L }
    }

    private fun seedLocation(id: String, ts: Long, lat: Double = 1.0, lon: Double = 2.0) {
        db.locationReportQueries.upsert(
            hashId = "hash-$id-$ts", beaconId = id, publishedAt = ts,
            description = "", timestamp = ts, confidence = 1,
            latitude = lat, longitude = lon, horizontalAccuracy = 5,
            status = 0, lastUpdate = ts,
        )
    }

    @Test
    fun `load emits points in the given range sorted newest first`() = runTest {
        seedLocation("b1", 100L)
        seedLocation("b1", 300L)
        seedLocation("b1", 200L)
        seedLocation("b1", 999_999L) // outside window
        val vm = HistoryViewModel(
            beaconRepo, "b1", scope = this, ioDispatcher = Dispatchers.Unconfined,
        )
        vm.load(0L, 500L)
        advanceUntilIdle()
        val points = vm.state.value.points.map { it.timestampMs }
        assertEquals(listOf(300L, 200L, 100L), points)
    }

    @Test
    fun `loadLast24h uses nowMs to pick the window`() = runTest {
        val nowMs = 10_000_000L
        seedLocation("b1", nowMs - 1_000L)
        seedLocation("b1", nowMs - 2L * 24 * 60 * 60 * 1000L) // older than 24h
        val vm = HistoryViewModel(
            beaconRepo = beaconRepo,
            beaconId = "b1",
            nowMs = { nowMs },
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
        )
        vm.loadLast24h()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.points.size)
    }

    @Test
    fun `fetchAndLoad persists returned reports and emits them`() = runTest {
        val fetched = listOf(
            BeaconLocationReport(
                publishedAt = 500L, description = "", timestamp = 500L,
                confidence = 1, latitude = 10.0, longitude = 20.0,
                horizontalAccuracy = 5, status = 0,
            )
        )
        val vm = HistoryViewModel(
            beaconRepo = beaconRepo,
            beaconId = "b1",
            fetchRange = { _, _, _ -> fetched },
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
        )
        vm.fetchAndLoad(0L, 1_000L)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertEquals(1, vm.state.value.points.size)
        assertEquals(10.0, vm.state.value.points.single().latitude)
    }

    @Test
    fun `fetchAndLoad records error on failure`() = runTest {
        val vm = HistoryViewModel(
            beaconRepo = beaconRepo,
            beaconId = "b1",
            fetchRange = { _, _, _ -> throw RuntimeException("boom") },
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
        )
        vm.fetchAndLoad(0L, 1L)
        advanceUntilIdle()
        assertEquals("boom", vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `fetchAndLoad with empty response leaves error null and emits cached`() = runTest {
        seedLocation("b1", 200L)
        val vm = HistoryViewModel(
            beaconRepo = beaconRepo,
            beaconId = "b1",
            fetchRange = { _, _, _ -> emptyList() },
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
        )
        vm.fetchAndLoad(0L, 1_000L)
        advanceUntilIdle()
        assertTrue(vm.state.value.error == null)
        assertEquals(1, vm.state.value.points.size)
    }
}
