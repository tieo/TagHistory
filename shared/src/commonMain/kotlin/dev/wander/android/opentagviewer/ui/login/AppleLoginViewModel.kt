package io.github.tieo.taghistory.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.apple.account.LoginResult
import io.github.tieo.taghistory.apple.account.TwoFactorChallenge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * KMP ViewModel for the login + 2FA flow. Replaces Java
 * `AppleLoginViewModel` + `LoginActivityState`. The Lombok-bean-with-
 * LiveData shape is gone — state is a `StateFlow<LoginUiState>` that
 * Compose re-renders on each emission.
 *
 * The ViewModel doesn't touch `AppleLoginService` directly. Instead the
 * host wires two lambdas: [startLogin] kicks off email+password auth
 * and returns a [LoginResult]; [onLoggedIn] runs once on success so the
 * host can persist the account blob and navigate. That seam lets unit
 * tests drive the state machine without standing up the full HTTP +
 * crypto + anisette stack.
 */
class AppleLoginViewModel(
    private val startLogin: suspend (email: String, password: String) -> LoginResult,
    private val onLoggedIn: suspend () -> Unit = {},
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope

    fun setEmail(email: String) = _state.update { it.copy(email = email, loginError = null) }
    fun setPassword(password: String) =
        _state.update { it.copy(password = password, loginError = null) }
    fun setTwoFactorCode(code: String) =
        _state.update { it.copy(twoFactorCode = code, twoFactorError = null) }

    fun chooseTwoFactorMethod(method: TwoFactorChallenge) {
        _state.update { it.copy(chosenTwoFactorMethod = method, twoFactorError = null) }
    }

    fun submitLogin() {
        val snapshot = _state.value
        if (snapshot.isLoggingIn) return
        if (!snapshot.isEmailValid || !snapshot.isPasswordValid) {
            _state.update { it.copy(loginError = "Enter email and password") }
            return
        }
        _state.update { it.copy(isLoggingIn = true, loginError = null) }
        runScope.launch {
            val outcome = try {
                startLogin(snapshot.email, snapshot.password)
            } catch (e: AppleLoginException) {
                _state.update { it.copy(isLoggingIn = false, loginError = e.message ?: "Login failed") }
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(isLoggingIn = false, loginError = e.message ?: "Login failed") }
                return@launch
            }
            applyResult(outcome)
        }
    }

    fun requestTwoFactorChallenge() {
        val method = _state.value.chosenTwoFactorMethod ?: return
        runScope.launch {
            try {
                method.request()
                _state.update {
                    it.copy(
                        page = LoginUiState.Page.ENTER_2FA_CODE,
                        twoFactorError = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(twoFactorError = e.message ?: "Could not send code") }
            }
        }
    }

    fun submitTwoFactorCode() {
        val snapshot = _state.value
        val method = snapshot.chosenTwoFactorMethod ?: return
        if (snapshot.isSubmittingTwoFactor) return
        if (snapshot.twoFactorCode.isBlank()) {
            _state.update { it.copy(twoFactorError = "Enter the code") }
            return
        }
        _state.update { it.copy(isSubmittingTwoFactor = true, twoFactorError = null) }
        runScope.launch {
            val outcome = try {
                method.submit(snapshot.twoFactorCode)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSubmittingTwoFactor = false,
                        failedTwoFactorAttempts = it.failedTwoFactorAttempts + 1,
                        twoFactorError = e.message ?: "Verification failed",
                    )
                }
                return@launch
            }
            applyResult(outcome)
        }
    }

    fun reset() {
        _state.value = LoginUiState()
    }

    private suspend fun applyResult(result: LoginResult) {
        when (result) {
            is LoginResult.RequireTwoFactor -> _state.update {
                it.copy(
                    isLoggingIn = false,
                    page = LoginUiState.Page.CHOOSE_2FA,
                    twoFactorMethods = result.methods,
                )
            }
            LoginResult.LoggedIn -> {
                onLoggedIn()
                _state.update {
                    it.copy(
                        isLoggingIn = false,
                        isSubmittingTwoFactor = false,
                        finished = true,
                    )
                }
            }
        }
    }
}
