package io.github.tieo.taghistory.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class HistoryScreenTest {

    private lateinit var db: TagHistoryDatabase
    private lateinit var beaconRepo: BeaconRepository

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
        beaconId: String = "b1",
        fetchRange: suspend (String, Long, Long) -> List<BeaconLocationReport> = { _, _, _ -> emptyList() },
    ) = HistoryViewModel(
        beaconRepo = beaconRepo,
        beaconId = beaconId,
        fetchRange = fetchRange,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    )

    @Test
    fun history_shows_no_history_message_when_empty() = runComposeUiTest {
        val vm = buildVm()
        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HistoryScreen(viewModel = vm, title = "My Keys", onBack = {})
                }
            }
        }
        waitUntil(timeoutMillis = 3_000L) { !vm.state.value.isLoading }
        onNodeWithText("No history yet").assertIsDisplayed()
    }

    @Test
    fun history_shows_error_message_on_fetch_failure() = runComposeUiTest {
        val vm = buildVm(
            fetchRange = { _, _, _ -> throw RuntimeException("connection timed out") },
        )
        setContent {
            TagHistoryTheme {
                Surface {
                    HistoryScreen(viewModel = vm, title = "My Keys", onBack = {})
                }
            }
        }
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.error != null }
        onNodeWithText("connection timed out").assertIsDisplayed()
    }

    @Test
    fun history_shows_today_chip_when_points_present() = runComposeUiTest {
        val nowMs = System.currentTimeMillis()
        seedLocation("b1", nowMs - 1_000L) // 1s ago = today
        val vm = buildVm()
        // Prime the VM state with the seeded point.
        vm.load(nowMs - 7L * 24 * 3600 * 1000L, nowMs)
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.points.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    HistoryScreen(viewModel = vm, title = "Tag", onBack = {})
                }
            }
        }
        onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun history_footer_shows_point_count() = runComposeUiTest {
        val nowMs = System.currentTimeMillis()
        seedLocation("b1", nowMs - 1_000L)
        seedLocation("b1", nowMs - 2_000L)
        val vm = buildVm()
        vm.load(nowMs - 7L * 24 * 3600 * 1000L, nowMs)
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.points.size == 2 }
        setContent {
            TagHistoryTheme {
                Surface {
                    HistoryScreen(viewModel = vm, title = "Tag", onBack = {})
                }
            }
        }
        // Footer text: "2 points"
        onNodeWithText("2 points").assertIsDisplayed()
    }

    @Test
    fun history_title_shown_in_top_bar() = runComposeUiTest {
        val vm = buildVm()
        setContent {
            TagHistoryTheme {
                Surface {
                    HistoryScreen(viewModel = vm, title = "AirTag Laptop", onBack = {})
                }
            }
        }
        onNodeWithText("AirTag Laptop").assertIsDisplayed()
    }

    @Test
    fun history_singular_point_count_shown_correctly() = runComposeUiTest {
        val nowMs = System.currentTimeMillis()
        seedLocation("b1", nowMs - 500L) // single point today
        val vm = buildVm()
        vm.load(nowMs - 7L * 24 * 3600 * 1000L, nowMs)
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.points.size == 1 }
        setContent {
            TagHistoryTheme {
                Surface {
                    HistoryScreen(viewModel = vm, title = "Tag", onBack = {})
                }
            }
        }
        // Singular: "1 point" not "1 points"
        onNodeWithText("1 point").assertIsDisplayed()
        onNodeWithText("1 points").assertDoesNotExist()
    }
}
