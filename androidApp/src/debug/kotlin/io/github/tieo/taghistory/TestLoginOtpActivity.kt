package io.github.tieo.taghistory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tieo.taghistory.apple.account.LoginResult
import io.github.tieo.taghistory.apple.account.TwoFactorChallenge
import io.github.tieo.taghistory.ui.login.AppleLoginViewModel
import io.github.tieo.taghistory.ui.login.LoginScreen
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import kotlinx.coroutines.delay

/**
 * Debug-only. The real sign-in screens against a stand-in for Apple, so the
 * second-factor pages (keyboard, layout) can be driven on an emulator:
 * `adb shell am start -n io.github.tieo.taghistory/.TestLoginOtpActivity`.
 * Any email and password ask for an SMS code; a full code stays submitting.
 */
class TestLoginOtpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = AppleLoginViewModel(
            startLogin = { _, _ -> LoginResult.RequireTwoFactor(listOf(TwoFactorChallenge.preview("+49 ••••• •••••78"))) },
            onLoggedIn = { delay(Long.MAX_VALUE) },
        )
        setContent {
            TagHistoryTheme { LoginScreen(viewModel = viewModel) }
        }
    }
}
