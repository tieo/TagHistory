package io.github.tieo.taghistory.ui.map

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.UserBeaconOptions
import io.github.tieo.taghistory.testutil.HookedDriver
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Threading contract of [MapViewModel]: its maps are confined to one serial
 * scope, work that leaves that scope gets snapshots, and a UI build from an
 * older snapshot can never be published over a newer one. The plain unit
 * tests run everything on one test dispatcher and cannot see any of this.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelConcurrencyTest {

    private fun newDb(driver: app.cash.sqldelight.db.SqlDriver): TagHistoryDatabase {
        TagHistoryDatabase.Schema.create(driver)
        return TagHistoryDatabase(driver)
    }

    private fun TagHistoryDatabase.seedBeacon(id: String, name: String) {
        beaconNamingRecordQueries.upsert(id = id, importId = null, version = "1", content = null, isRemoved = false)
        ownedBeaconQueries.upsert(id = id, importId = null, content = "plist", version = "1", isRemoved = false)
        userBeaconOptionsQueries.upsert(beaconId = id, lastUpdate = 0L, uiName = name, uiEmoji = null)
    }

    private fun report(ts: Long) = BeaconLocationReport(
        publishedAt = ts, description = "", timestamp = ts, confidence = 1,
        latitude = 1.0, longitude = 2.0, horizontalAccuracy = 10, status = 0,
    )

    private fun authRepo() = UserAuthRepository(
        settings = PropertiesSettings(Properties()),
        crypto = SecureBlobStore(),
        keystoreAlias = "test",
    ).apply { storeUserAuth("""{"account":null}""".encodeToByteArray()) }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `a build from an older snapshot finishing last does not overwrite fresher cards or markers`() {
        val driver = HookedDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties()))
        val db = newDb(driver)
        val repo = BeaconRepository(db)
        db.seedBeacon("b1", "Old")
        repo.storeToLocationCache(mapOf("b1" to listOf(report(100))))

        // Production-shaped threading: one serial "main" thread, real IO pool.
        val confined = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(confined + SupervisorJob() + CoroutineExceptionHandler { _, t -> errors += t })
        try {
            val vm = MapViewModel(
                beaconRepo = repo,
                userDataRepo = UserDataRepository(PropertiesSettings(Properties())),
                authRepo = authRepo(),
                fetchReports = { _, _ -> emptyMap() },
                minRefreshIntervalMs = 0L,
                scope = scope,
                ioDispatcher = Dispatchers.IO,
            )
            awaitUntil("boot shows Old") {
                vm.state.value.cards.singleOrNull()?.displayName == "Old" && !vm.state.value.isRefreshing
            }
            // Drop the name cache so the next build reads the table (and hits the hook).
            repo.storeUserBeaconOptions(UserBeaconOptions("b1", 1L, "Old", null))

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val armed = AtomicBoolean(true)
            driver.afterQuery = { sql ->
                if ("FROM UserBeaconOptions" in sql && armed.compareAndSet(true, false)) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    exited.countDown()
                }
            }

            // Build #1: a new fix triggers the observer, whose build reads the
            // old name and then parks.
            repo.storeToLocationCache(mapOf("b1" to listOf(report(200))))
            assertTrue(entered.await(10, TimeUnit.SECONDS), "build #1 never reached the names query")

            // Build #2 starts later, reads the new name, and publishes first.
            vm.renameBeacon("b1", "New")
            awaitUntil("rename published") {
                vm.state.value.cards.single().displayName == "New" &&
                    vm.state.value.markers.single().displayName == "New"
            }

            // Let build #1 finish. It must not win.
            release.countDown()
            assertTrue(exited.await(10, TimeUnit.SECONDS))
            val watchUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_000)
            while (System.nanoTime() < watchUntil) {
                val s = vm.state.value
                val card = s.cards.single().displayName
                val marker = s.markers.single().displayName
                if (card != "New" || marker != "New") {
                    fail("stale build overwrote the rename: card '$card', marker '$marker'")
                }
                Thread.sleep(5)
            }
            assertEquals(200L, vm.state.value.cards.single().lastUpdatedMs)
            assertTrue(errors.isEmpty(), "uncaught in VM scope: $errors")
        } finally {
            scope.cancel()
            confined.close()
        }
    }

    @Test
    fun `fetchReports is handed a snapshot, not the live beacon map`() = runTest {
        val db = newDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties()))
        val repo = BeaconRepository(db)
        db.seedBeacon("b1", "One")
        db.seedBeacon("b2", "Two")
        repo.storeToLocationCache(mapOf("b1" to listOf(report(100)), "b2" to listOf(report(100))))

        var handedToFetch: Map<String, BeaconData>? = null
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            val vm = MapViewModel(
                beaconRepo = repo,
                userDataRepo = UserDataRepository(PropertiesSettings(Properties())),
                authRepo = authRepo(),
                fetchReports = { beacons, _ -> handedToFetch = beacons; emptyMap() },
                minRefreshIntervalMs = 0L,
                scope = scope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            advanceUntilIdle()
            assertEquals(setOf("b1", "b2"), handedToFetch?.keys)

            // The fetch runs on the IO dispatcher; a removal on the confined
            // scope must not reach into the map it is iterating.
            vm.removeBeacon("b2")
            advanceUntilIdle()
            assertEquals(setOf("b1", "b2"), handedToFetch?.keys)
        } finally {
            scope.cancel()
        }
    }
}
