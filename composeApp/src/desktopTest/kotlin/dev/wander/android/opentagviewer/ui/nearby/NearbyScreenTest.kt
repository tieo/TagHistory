package io.github.tieo.taghistory.ui.nearby

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The BLE scan follows the screen's lifecycle, not just its composition:
 * backgrounding the app with Nearby open stops the activity but keeps the
 * composition, and the scan must stop with it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class NearbyScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun scan_stops_when_the_screen_stops_and_resumes_when_it_starts_again() = runComposeUiTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        val scans = mutableListOf<Job>()
        val vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val vm = NearbyViewModel(
                beaconRepo = BeaconRepository(TagHistoryDatabase(driver)),
                loadOwnedTags = { emptyList() },
                startBleScan = { _, _ -> Job().also { scans += it } },
                scope = vmScope,
            )
            val owner = FakeLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    TagHistoryTheme { NearbyScreen(viewModel = vm) }
                }
            }
            waitForIdle()
            assertEquals(1, scans.size, "scan starts with the screen")
            assertTrue(scans[0].isActive)

            // App backgrounded: activity stopped, composition still alive.
            runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            waitForIdle()
            assertTrue(scans[0].isCancelled, "scan must stop when the screen stops")
            assertEquals(ScanState.IDLE, vm.state.value.scanState)

            // Back in the foreground: a fresh scan.
            runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitForIdle()
            assertEquals(2, scans.size)
            assertTrue(scans[1].isActive)
        } finally {
            vmScope.cancel()
        }
    }
}
