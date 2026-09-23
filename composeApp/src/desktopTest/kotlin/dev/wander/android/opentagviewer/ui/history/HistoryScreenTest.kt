package io.github.tieo.taghistory.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import java.util.Properties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The screen triggers its own load when it appears, and the view model does
 * that work on a background dispatcher the Compose harness cannot see. So no
 * test pre-loads the VM or asserts straight after setContent: each waits on
 * [HistoryUiState.hasLoaded] for the screen's own read to land, then checks
 * what is drawn.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class HistoryScreenTest {

    private lateinit var db: TagHistoryDatabase
    private lateinit var beaconRepo: BeaconRepository
    private val vmScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        db = TagHistoryDatabase(driver)
        beaconRepo = BeaconRepository(db) { 1_000L }
    }

    @AfterTest
    fun tearDown() {
        vmScopes.forEach { it.cancel() }
        vmScopes.clear()
        Dispatchers.resetMain()
    }

    private fun seedLocation(id: String, ts: Long) {
        db.locationReportQueries.upsert(
            hashId = "h-$id-$ts", beaconId = id, publishedAt = ts,
            description = "", timestamp = ts, confidence = 1,
            latitude = 1.0, longitude = 2.0, horizontalAccuracy = 5,
            status = 0, lastUpdate = ts,
        )
    }

    private fun buildVm(
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) = HistoryViewModel(
        beaconRepo = beaconRepo,
        beaconId = "b1",
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { vmScopes += it },
        ioDispatcher = ioDispatcher,
    )

    private fun ComposeUiTest.show(vm: HistoryViewModel, title: String = "Tag") {
        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HistoryScreen(viewModel = vm, title = title, onBack = {})
                }
            }
        }
    }

    @Test
    fun history_title_shown_in_top_bar() = runComposeUiTest {
        val vm = buildVm()
        show(vm, title = "AirTag Laptop")
        onNodeWithText("AirTag Laptop").assertIsDisplayed()
    }

    @Test
    fun history_says_loading_until_the_first_read_lands() = runComposeUiTest {
        // An IO dispatcher on its own, never-advanced scheduler keeps the read
        // pending, which is the state the header must not call "No data".
        val vm = buildVm(ioDispatcher = StandardTestDispatcher(TestCoroutineScheduler()))
        show(vm)
        onNodeWithText("Loading…").assertIsDisplayed()
        onNodeWithText("No data").assertDoesNotExist()
    }

    @Test
    fun history_says_no_data_once_an_empty_read_has_landed() = runComposeUiTest {
        val vm = buildVm()
        show(vm)
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.hasLoaded }
        onNodeWithText("No data").assertIsDisplayed()
        onNodeWithText("Loading…").assertDoesNotExist()
    }

    @Test
    fun history_labels_todays_points_as_today() = runComposeUiTest {
        seedLocation("b1", System.currentTimeMillis())
        val vm = buildVm()
        show(vm)
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.hasLoaded && vm.state.value.points.isNotEmpty() }
        onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun history_announces_the_point_count_with_its_unit() = runComposeUiTest {
        val now = System.currentTimeMillis()
        seedLocation("b1", now - 1_000L)
        seedLocation("b1", now - 2_000L)
        val vm = buildVm()
        show(vm)
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.points.size == 2 }
        onNodeWithContentDescription("2 points").assertIsDisplayed()
    }

    @Test
    fun history_announces_a_single_point_in_the_singular() = runComposeUiTest {
        seedLocation("b1", System.currentTimeMillis() - 500L)
        val vm = buildVm()
        show(vm)
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.points.size == 1 }
        onNodeWithContentDescription("1 point").assertIsDisplayed()
        onNodeWithContentDescription("1 points").assertDoesNotExist()
    }

}
