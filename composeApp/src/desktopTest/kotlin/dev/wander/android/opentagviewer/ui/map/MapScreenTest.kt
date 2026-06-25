package io.github.tieo.taghistory.ui.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
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
class MapScreenTest {

    private lateinit var db: TagHistoryDatabase
    private lateinit var beaconRepo: BeaconRepository
    private lateinit var userDataRepo: UserDataRepository
    private lateinit var authRepo: UserAuthRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        db = TagHistoryDatabase(driver)
        beaconRepo = BeaconRepository(db) { 1_000L }
        userDataRepo = UserDataRepository(PropertiesSettings(Properties()))
        authRepo = UserAuthRepository(
            settings = PropertiesSettings(Properties()),
            crypto = SecureBlobStore(),
            keystoreAlias = "test",
        )
        authRepo.storeUserAuth("""{"account":null}""".encodeToByteArray())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedBeacon(id: String, name: String) {
        db.beaconNamingRecordQueries.upsert(
            id = id, importId = null, version = "1", content = null, isRemoved = false,
        )
        db.ownedBeaconQueries.upsert(
            id = id, importId = null, content = "plist", version = "1", isRemoved = false,
        )
        db.userBeaconOptionsQueries.upsert(
            beaconId = id, lastUpdate = 0L, uiName = name, uiEmoji = null,
        )
    }

    private fun seedLocation(id: String, ts: Long, lat: Double = 1.0, lon: Double = 2.0) {
        db.locationReportQueries.upsert(
            hashId = "h-$id-$ts", beaconId = id, publishedAt = ts,
            description = "", timestamp = ts, confidence = 1,
            latitude = lat, longitude = lon, horizontalAccuracy = 10,
            status = 0, lastUpdate = ts,
        )
    }

    private fun buildVm(
        fetchReports: suspend (Map<String, BeaconData>, Int) -> Map<String, List<BeaconLocationReport>> =
            { _, _ -> emptyMap() },
    ) = MapViewModel(
        beaconRepo = beaconRepo,
        userDataRepo = userDataRepo,
        authRepo = authRepo,
        fetchReports = fetchReports,
        minRefreshIntervalMs = 0L,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        hoursBack = 24,
    )

    @Test
    fun map_shows_shimmer_while_initial_fetch_in_progress() = runComposeUiTest {
        // Beacon exists but fetch suspends indefinitely → isInitialFetchComplete stays false
        // and no cards → shimmer shown.
        seedBeacon("b1", "Keys")
        val vm = buildVm(fetchReports = { _, _ -> kotlinx.coroutines.suspendCancellableCoroutine { } })
        // Wait until the VM is actually fetching (isRefreshing=true) so the shimmer is shown.
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.isRefreshing }
        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MapScreen(
                        viewModel = vm,
                    )
                }
            }
        }
        onNodeWithText("No AirTags yet").assertDoesNotExist()
    }

    @Test
    fun map_shows_empty_card_when_no_beacons_and_fetch_done() = runComposeUiTest {
        // No beacons in DB. Build VM with instant-complete fetch (empty result).
        val vm = buildVm(fetchReports = { _, _ -> emptyMap() })
        // Wait for isInitialFetchComplete to be set.
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.isInitialFetchComplete }
        setContent {
            TagHistoryTheme {
                Surface {
                    MapScreen(viewModel = vm)
                }
            }
        }
        onNodeWithText("No AirTags yet").assertIsDisplayed()
    }

    @Test
    fun map_shows_tag_cards_when_beacons_present_with_location() = runComposeUiTest {
        seedBeacon("b1", "My Keys")
        seedLocation("b1", ts = System.currentTimeMillis() - 5_000L)
        val vm = buildVm(fetchReports = { _, _ -> emptyMap() })
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.cards.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    MapScreen(viewModel = vm)
                }
            }
        }
        onNodeWithText("My Keys").assertIsDisplayed()
    }

    @Test
    fun map_shows_details_and_history_actions_on_tag_card() = runComposeUiTest {
        seedBeacon("b1", "Wallet")
        seedLocation("b1", ts = System.currentTimeMillis() - 10_000L)
        val vm = buildVm(fetchReports = { _, _ -> emptyMap() })
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.cards.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    MapScreen(viewModel = vm)
                }
            }
        }
        onNodeWithText("Details").assertIsDisplayed()
        onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun map_shows_no_location_for_beacon_without_recent_report() = runComposeUiTest {
        seedBeacon("b1", "Lost Bag")
        // No location seeded
        val vm = buildVm(fetchReports = { _, _ -> emptyMap() })
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.isInitialFetchComplete }
        setContent {
            TagHistoryTheme {
                Surface {
                    MapScreen(viewModel = vm)
                }
            }
        }
        // Card shows even without a location, but address says "No recent location"
        onNodeWithText("Lost Bag").assertIsDisplayed()
        onNodeWithText("No recent location").assertIsDisplayed()
    }

    @Test
    fun map_shows_import_button_on_empty_card_when_callback_provided() = runComposeUiTest {
        val vm = buildVm(fetchReports = { _, _ -> emptyMap() })
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.isInitialFetchComplete }
        setContent {
            TagHistoryTheme {
                Surface {
                    MapScreen(
                        viewModel = vm,
                        onImport = { null },
                    )
                }
            }
        }
        onNodeWithTag("btn_import").assertIsDisplayed()
    }

    @Test
    fun map_no_import_button_when_no_callback() = runComposeUiTest {
        val vm = buildVm(fetchReports = { _, _ -> emptyMap() })
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.isInitialFetchComplete }
        setContent {
            TagHistoryTheme {
                Surface {
                    MapScreen(
                        viewModel = vm,
                        onImport = null,
                    )
                }
            }
        }
        onNodeWithTag("btn_import").assertDoesNotExist()
    }
}
