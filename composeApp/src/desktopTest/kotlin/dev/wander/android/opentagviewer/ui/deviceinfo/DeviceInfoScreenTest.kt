package io.github.tieo.taghistory.ui.deviceinfo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.runComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DeviceInfoScreenTest {

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

    private fun seedBeacon(id: String, name: String, emoji: String? = null) {
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

    private fun seedLocation(id: String, ts: Long, lat: Double = 1.0, lon: Double = 2.0) {
        db.locationReportQueries.upsert(
            hashId = "hash-$id-$ts", beaconId = id, publishedAt = ts,
            description = "", timestamp = ts, confidence = 1,
            latitude = lat, longitude = lon, horizontalAccuracy = 10,
            status = 0, lastUpdate = ts,
        )
    }

    private fun buildVm(id: String) = DeviceInfoViewModel(
        beaconRepo = beaconRepo,
        beaconId = id,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    )

    @Test
    fun deviceinfo_shows_beacon_name_in_title() = runComposeUiTest {
        seedBeacon("b1", "My Keys", "🔑")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName == "My Keys" }
        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DeviceInfoScreen(
                        viewModel = vm,
                        onBack = {},
                        onOpenHistory = {},
                    )
                }
            }
        }
        onNodeWithText("My Keys").assertIsDisplayed()
    }

    @Test
    fun deviceinfo_shows_last_seen_when_location_present() = runComposeUiTest {
        seedBeacon("b1", "Wallet")
        seedLocation("b1", ts = System.currentTimeMillis() - 30_000L)
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.lastLocation != null }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithText("Last seen").assertIsDisplayed()
        onNodeWithText("just now").assertIsDisplayed()
    }

    @Test
    fun deviceinfo_rename_button_opens_dialog() = runComposeUiTest {
        seedBeacon("b1", "Keys")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithTag("btn_rename").performClick()
        onNodeWithText("Rename device").assertIsDisplayed()
    }

    @Test
    fun deviceinfo_rename_dialog_cancel_dismisses() = runComposeUiTest {
        seedBeacon("b1", "Keys")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithTag("btn_rename").performClick()
        onNodeWithTag("btn_rename_cancel").performClick()
        onNodeWithText("Rename device").assertDoesNotExist()
    }

    @Test
    fun deviceinfo_rename_save_enabled_when_name_is_present() = runComposeUiTest {
        seedBeacon("b1", "Keys")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName == "Keys" }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithTag("btn_rename").performClick()
        onNodeWithTag("btn_rename_save").assertIsEnabled()
    }

    @Test
    fun deviceinfo_rename_save_disabled_when_name_cleared() = runComposeUiTest {
        seedBeacon("b1", "Keys")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName == "Keys" }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithTag("btn_rename").performClick()
        onNodeWithTag("field_rename_name").performTextClearance()
        onNodeWithTag("btn_rename_save").assertIsNotEnabled()
    }

    @Test
    fun deviceinfo_remove_button_shows_confirmation_dialog() = runComposeUiTest {
        seedBeacon("b1", "Keys")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithTag("btn_remove").performClick()
        onNodeWithText("Remove device?").assertIsDisplayed()
    }

    @Test
    fun deviceinfo_remove_confirm_sets_removed_state() = runComposeUiTest {
        seedBeacon("b1", "Keys")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithTag("btn_remove").performClick()
        onNodeWithTag("btn_remove_confirm").performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.removed }
        assertTrue(vm.state.value.removed)
    }

    @Test
    fun deviceinfo_history_button_invokes_callback() = runComposeUiTest {
        seedBeacon("b1", "Keys")
        val vm = buildVm("b1")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.displayName.isNotEmpty() }
        var historyId: String? = null
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(
                        viewModel = vm,
                        onBack = {},
                        onOpenHistory = { historyId = it },
                    )
                }
            }
        }
        onNodeWithTag("btn_view_history").performClick()
        assertEquals("b1", historyId)
    }

    @Test
    fun deviceinfo_unknown_beacon_shows_id_snippet_as_name() = runComposeUiTest {
        db.ownedBeaconQueries.upsert(
            id = "abcdef12345678", importId = null, content = "plist", version = "1",
            isRemoved = false,
        )
        val vm = buildVm("abcdef12345678")
        vm.load()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.beaconId.isNotEmpty() }
        setContent {
            TagHistoryTheme {
                Surface {
                    DeviceInfoScreen(viewModel = vm, onBack = {}, onOpenHistory = {})
                }
            }
        }
        onNodeWithText("abcdef12").assertIsDisplayed()
    }
}
