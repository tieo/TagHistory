package io.github.tieo.taghistory.ui.login

import io.github.tieo.taghistory.apple.account.TwoFactorChallenge

/**
 * Immutable Compose-facing snapshot of the login flow. Replaces the
 * Java `LoginActivityState` Lombok bean — the shape is the same, but the
 * mutation discipline is different: a single [AppleLoginViewModel]-owned
 * `StateFlow<LoginUiState>` emits copies, and screens are re-renders of
 * whatever the latest value is.
 */
data class LoginUiState(
    val page: Page = Page.LOGIN,
    val email: String = "",
    val password: String = "",
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val twoFactorMethods: List<TwoFactorChallenge> = emptyList(),
    val chosenTwoFactorMethod: TwoFactorChallenge? = null,
    val twoFactorCode: String = "",
    val isSubmittingTwoFactor: Boolean = false,
    val twoFactorError: String? = null,
    val failedTwoFactorAttempts: Int = 0,
    val finished: Boolean = false,
) {
    val isEmailValid: Boolean get() = email.isNotBlank() &&
        (email.contains('@') || email.all { it.isDigit() || it == '+' || it == '-' || it == ' ' })
    val isPasswordValid: Boolean get() = password.length >= MIN_PASSWORD_LENGTH

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }

    enum class Page { LOGIN, CHOOSE_2FA, ENTER_2FA_CODE }
}
