package io.github.tieo.taghistory.ui.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.apple.account.LoginResult
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * UI tests for [LoginScreen] LOGIN page. The 2FA flows are tested as
 * VM-level state transitions (the sealed [TwoFactorChallenge] subclasses
 * have `internal` constructors that aren't reachable from this module).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LoginScreenTest {

    // Compose runtime expects a Main dispatcher even when the VM uses a
    // separate scope for its launches. Wire a test dispatcher so the
    // `LaunchedEffect`/recomposition machinery has somewhere to land.
    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    // VM defaults to viewModelScope (Main) — we substitute a Default-backed
    // CoroutineScope so tests don't have to wait on the test scheduler.
    private fun stubVm(
        startLogin: suspend (String, String) -> LoginResult = { _, _ -> LoginResult.LoggedIn },
        onLoggedIn: suspend () -> Unit = {},
    ) = AppleLoginViewModel(
        startLogin = startLogin,
        onLoggedIn = onLoggedIn,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    )

    @Test
    fun login_form_renders_hero_and_inputs() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LoginScreen(viewModel = stubVm())
                }
            }
        }
        onNodeWithText("Welcome to TagHistory").assertIsDisplayed()
        onNodeWithText("Sign in with your Apple ID to fetch AirTag locations.")
            .assertIsDisplayed()
        onNodeWithText("Apple ID email or phone").assertIsDisplayed()
        onNodeWithText("Password").assertIsDisplayed()
    }

    @Test
    fun login_button_disabled_when_email_or_password_empty() = runComposeUiTest {
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = stubVm()) } }
        }
        onNodeWithTag("btn_login").assertIsNotEnabled()
        onNodeWithTag("field_email").performTextInput("a@b.co")
        onNodeWithTag("btn_login").assertIsNotEnabled()
    }

    @Test
    fun login_button_enables_when_both_fields_valid() = runComposeUiTest {
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = stubVm()) } }
        }
        onNodeWithTag("field_email").performTextInput("user@example.com")
        onNodeWithTag("field_password").performTextInput("hunter2!!")
        onNodeWithTag("btn_login").assertIsEnabled().assertHasClickAction()
    }

    @Test
    fun login_button_disabled_with_short_password() = runComposeUiTest {
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = stubVm()) } }
        }
        onNodeWithTag("field_email").performTextInput("user@example.com")
        onNodeWithTag("field_password").performTextInput("a") // too short
        onNodeWithTag("btn_login").assertIsNotEnabled()
    }

    @Test
    fun login_thrown_exception_renders_message_in_red() = runComposeUiTest {
        val vm = stubVm(startLogin = { _, _ ->
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_CREDENTIALS,
                "Account locked for security reasons",
            )
        })
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = vm) } }
        }
        onNodeWithTag("field_email").performTextInput("user@example.com")
        onNodeWithTag("field_password").performTextInput("hunter2!!")
        onNodeWithTag("btn_login").performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.loginError != null }
        onNodeWithText("Account locked for security reasons").assertIsDisplayed()
    }

    @Test
    fun submitLogin_with_blank_inputs_emits_validation_error() = runComposeUiTest {
        val vm = stubVm()
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = vm) } }
        }
        // Bypass UI gating; fire submit directly.
        vm.submitLogin()
        // VM should produce its built-in validation message.
        assertEquals("Enter email and password", vm.state.value.loginError)
    }

    @Test
    fun successful_login_marks_state_finished() = runComposeUiTest {
        var loggedInCalls = 0
        val vm = stubVm(
            startLogin = { _, _ -> LoginResult.LoggedIn },
            onLoggedIn = { loggedInCalls++ },
        )
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = vm) } }
        }
        onNodeWithTag("field_email").performTextInput("user@example.com")
        onNodeWithTag("field_password").performTextInput("hunter2!!")
        onNodeWithTag("btn_login").performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.finished }
        assertEquals(1, loggedInCalls)
        assertEquals(LoginUiState.Page.LOGIN, vm.state.value.page)
    }

    @Test
    fun typing_in_either_field_clears_prior_login_error() = runComposeUiTest {
        val vm = stubVm(startLogin = { _, _ ->
            throw AppleLoginException(AppleLoginException.Kind.INVALID_CREDENTIALS, "Boom")
        })
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = vm) } }
        }
        // Trigger an error.
        onNodeWithTag("field_email").performTextInput("user@example.com")
        onNodeWithTag("field_password").performTextInput("hunter2!!")
        onNodeWithTag("btn_login").performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.state.value.loginError == "Boom" }
        // Editing either field should drop the error.
        vm.setEmail("user@example.org")
        assertEquals(null, vm.state.value.loginError)
    }

    @Test
    fun reset_returns_to_login_page_with_empty_fields() = runComposeUiTest {
        val vm = stubVm()
        setContent {
            TagHistoryTheme { Surface { LoginScreen(viewModel = vm) } }
        }
        vm.setEmail("test@x.co")
        vm.setPassword("longenough")
        vm.setTwoFactorCode("123456")
        assertNotEquals("", vm.state.value.email)
        vm.reset()
        assertEquals(LoginUiState.Page.LOGIN, vm.state.value.page)
        assertEquals("", vm.state.value.email)
        assertEquals("", vm.state.value.password)
        assertTrue(vm.state.value.twoFactorCode.isEmpty())
    }
}
