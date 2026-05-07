package io.github.tieo.taghistory.apple.account

/**
 * Outcome of a call to [AppleLoginService.login] or a
 * [TwoFactorChallenge.submit].
 *
 * Replaces the Java port's "returns the account's LoginState enum"
 * pattern. Using a sealed hierarchy lets callers pattern-match on the
 * two genuinely distinct outcomes — a finished login vs. "we still need
 * a 2FA code" — without string-comparing enum values or re-reading the
 * account's state machine after the call.
 *
 * There is deliberately no `Failed` variant: any failure path throws
 * [AppleLoginException] so exception-based control flow stays uniform
 * with the rest of the login pipeline (crypto errors, network errors,
 * plist shape errors all bubble up the same way).
 */
sealed interface LoginResult {

    /** GSA accepted username + password with no 2FA required, and MobileMe
     * delegate exchange succeeded. The owning account is now [LoginState.LOGGED_IN]. */
    data object LoggedIn : LoginResult

    /** GSA returned `secondaryAuth` / `trustedDeviceSecondaryAuth` — the
     * caller must drive one of [methods] through
     * [TwoFactorChallenge.request] + [TwoFactorChallenge.submit] to
     * finish. The owning account is now [LoginState.REQUIRE_2FA]. */
    data class RequireTwoFactor(val methods: List<TwoFactorChallenge>) : LoginResult
}
