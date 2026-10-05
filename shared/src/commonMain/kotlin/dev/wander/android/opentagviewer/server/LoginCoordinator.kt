package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.apple.account.LoginResult
import io.github.tieo.taghistory.apple.account.TwoFactorChallenge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives one Apple sign-in at a time on behalf of a remote client. Apple's
 * flow is stateful (the SRP exchange, then a second factor against the same
 * session), so the account and the offered challenges are kept here between
 * the client's requests. A new sign-in replaces any unfinished one.
 */
class LoginCoordinator(
    /** Starts Apple's sign-in for a fresh account; production wraps `AppleLoginService.login`. */
    private val signIn: suspend (account: AppleAccount, email: String, password: String) -> LoginResult,
    /** Persists a finished account; the sync loop reads it from there. */
    private val onLoggedIn: suspend (AppleAccount) -> Unit,
) {
    private class Pending(val account: AppleAccount, val challenges: List<TwoFactorChallenge>)

    private val lock = Mutex()
    private var pending: Pending? = null

    suspend fun login(email: String, password: String): LoginResponse = lock.withLock {
        pending = null
        val account = AppleAccount()
        finish(account, signIn(account, email, password))
    }

    suspend fun requestTwoFactor(method: TwoFactorMethodDto) = lock.withLock {
        challengeFor(method).request()
    }

    suspend fun submitTwoFactor(method: TwoFactorMethodDto, code: String): LoginResponse = lock.withLock {
        val current = pending ?: throw noPendingLogin()
        finish(current.account, challengeFor(method).submit(code))
    }

    private suspend fun finish(account: AppleAccount, result: LoginResult): LoginResponse = when (result) {
        LoginResult.LoggedIn -> {
            pending = null
            onLoggedIn(account)
            LoginResponse(loggedIn = true)
        }
        is LoginResult.RequireTwoFactor -> {
            pending = Pending(account, result.methods)
            LoginResponse(loggedIn = false, methods = result.methods.map { it.toDto() })
        }
    }

    private fun challengeFor(method: TwoFactorMethodDto): TwoFactorChallenge {
        val current = pending ?: throw noPendingLogin()
        // Matched by kind and phone number id; the number is display text.
        return current.challenges.firstOrNull {
            val offered = it.toDto()
            offered.kind == method.kind && offered.phoneNumberId == method.phoneNumberId
        }
            ?: throw AppleLoginException(AppleLoginException.Kind.INVALID_STATE, "Unknown second-factor method")
    }

    private fun noPendingLogin() =
        AppleLoginException(AppleLoginException.Kind.INVALID_STATE, "No sign-in is waiting for a second factor")
}

fun TwoFactorChallenge.toDto(): TwoFactorMethodDto = when (this) {
    is TwoFactorChallenge.Sms -> TwoFactorMethodDto(
        kind = TwoFactorMethodDto.Kind.SMS,
        phoneNumberId = phoneNumberId,
        phoneNumber = phoneNumber,
    )
    is TwoFactorChallenge.TrustedDevice -> TwoFactorMethodDto(kind = TwoFactorMethodDto.Kind.TRUSTED_DEVICE)
}
