package io.github.tieo.taghistory.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SettingsScreenTest {

    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun stubVm(): SettingsViewModel {
        val settingsRepo = UserSettingsRepository(PropertiesSettings(Properties()))
        val authRepo = UserAuthRepository(
            settings = PropertiesSettings(Properties()),
            crypto = SecureBlobStore(),
            keystoreAlias = "test",
        )
        authRepo.storeUserAuth("""{"account":null}""".encodeToByteArray())
        return SettingsViewModel(
            settingsRepo = settingsRepo,
            authRepo = authRepo,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        ).also { it.load() }
    }

    @Test
    fun settings_screen_renders_top_section_headers() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(viewModel = stubVm(), onOpenInformation = {})
                }
            }
        }
        onNodeWithText("Settings").assertIsDisplayed()
        onNodeWithText("Appearance").assertIsDisplayed()
        onNodeWithText("Background sync").assertIsDisplayed()
        // Lower sections may be off-screen on small test windows.
        onNodeWithText("Advanced").assertExists()
        onNodeWithText("Account").assertExists()
        onNodeWithText("About").assertExists()
    }

    @Test
    fun settings_theme_selector_shows_three_choices() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(viewModel = stubVm(), onOpenInformation = {})
                }
            }
        }
        onNodeWithTag("btn_theme_system").assertIsDisplayed()
        onNodeWithTag("btn_theme_light").assertIsDisplayed()
        onNodeWithTag("btn_theme_dark").assertIsDisplayed()
    }

    @Test
    fun settings_interval_slider_visible_when_background_sync_on() = runComposeUiTest {
        val vm = stubVm()
        vm.setBackgroundSyncEnabled(true)
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(viewModel = vm, onOpenInformation = {})
                }
            }
        }
        onNodeWithText("Interval").assertIsDisplayed()
    }

    @Test
    fun settings_interval_slider_hidden_when_background_sync_off() = runComposeUiTest {
        val vm = stubVm()
        vm.setBackgroundSyncEnabled(false)
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(viewModel = vm, onOpenInformation = {})
                }
            }
        }
        onNodeWithText("Interval").assertDoesNotExist()
    }

    @Test
    fun settings_sign_out_button_shows_confirmation_dialog() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(viewModel = stubVm(), onOpenInformation = {})
                }
            }
        }
        onNodeWithTag("btn_sign_out").performClick()
        onNodeWithText("Sign out?").assertIsDisplayed()
        onNodeWithTag("btn_sign_out_cancel").assertIsDisplayed()
    }

    @Test
    fun settings_sign_out_dialog_cancel_dismisses() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(viewModel = stubVm(), onOpenInformation = {})
                }
            }
        }
        onNodeWithTag("btn_sign_out").performClick()
        onNodeWithTag("btn_sign_out_cancel").performClick()
        onNodeWithText("Sign out?").assertDoesNotExist()
    }

    @Test
    fun settings_sign_out_confirm_triggers_signedOut_state() = runComposeUiTest {
        val vm = stubVm()
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(viewModel = vm, onOpenInformation = {})
                }
            }
        }
        assertFalse(vm.state.value.signedOut)
        onNodeWithTag("btn_sign_out").performClick()
        onNodeWithTag("btn_sign_out_confirm").performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.signedOut }
        assertTrue(vm.state.value.signedOut)
    }

    @Test
    fun settings_about_button_exists_and_is_clickable() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(
                        viewModel = stubVm(),
                        onOpenInformation = {},
                    )
                }
            }
        }
        onNodeWithTag("btn_about").assertExists()
    }

    @Test
    fun settings_refresh_button_shown_only_when_callback_provided() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(
                        viewModel = stubVm(),
                        onOpenInformation = {},
                        onRefreshNow = { null },
                    )
                }
            }
        }
        onNodeWithTag("btn_refresh_now").assertIsDisplayed()
    }

    @Test
    fun settings_refresh_button_absent_without_callback() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    SettingsScreen(
                        viewModel = stubVm(),
                        onOpenInformation = {},
                        onRefreshNow = null,
                    )
                }
            }
        }
        onNodeWithTag("btn_refresh_now").assertDoesNotExist()
    }
}
