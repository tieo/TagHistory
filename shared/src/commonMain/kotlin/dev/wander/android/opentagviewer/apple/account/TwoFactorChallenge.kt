package io.github.tieo.taghistory.apple.account

/**
 * A 2FA method the user can pick from after GSA returns
 * `secondaryAuth` / `trustedDeviceSecondaryAuth`. Sealed so UI code can
 * branch exhaustively on the variants instead of `instanceof`-checking
 * subclasses as the Java port did.
 *
 * Both variants delegate the network dance back to the
 * [TwoFactorCoordinator] that produced them — it holds the account state
 * and the HTTP client, and is the only place that knows how to actually
 * hit gsa.apple.com.
 */
sealed class TwoFactorChallenge {

    abstract val displayName: String
    protected abstract val coordinator: TwoFactorCoordinator

    /** Ask Apple to dispatch the challenge (send SMS text, push to trusted device). */
    suspend fun request() = when (this) {
        is Sms -> coordinator.requestSms(phoneNumberId)
        is TrustedDevice -> coordinator.requestTrustedDevice()
    }

    /**
     * Submit the one-time code the user entered. Drives GSA re-auth
     * and MobileMe delegate exchange on success, returning
     * [LoginResult.LoggedIn]. Never returns [LoginResult.RequireTwoFactor]
     * — a second 2FA prompt after submitting one would be a server-side
     * protocol violation and surfaces as [AppleLoginException].
     */
    suspend fun submit(code: String): LoginResult = when (this) {
        is Sms -> coordinator.submitSms(phoneNumberId, code)
        is TrustedDevice -> coordinator.submitTrustedDevice(code)
    }

    /** Numeric SMS code sent to the given phone number. */
    @ConsistentCopyVisibility
    data class Sms internal constructor(
        val phoneNumberId: Int,
        val phoneNumber: String,
        override val coordinator: TwoFactorCoordinator,
    ) : TwoFactorChallenge() {
        override val displayName: String get() = "SMS $phoneNumber"
    }

    /** Push-style prompt on another Apple device the user is signed into. */
    @ConsistentCopyVisibility
    data class TrustedDevice internal constructor(
        override val coordinator: TwoFactorCoordinator,
    ) : TwoFactorChallenge() {
        override val displayName: String get() = "Trusted device"
    }
}

/**
 * Callback surface a [TwoFactorChallenge] uses to actually move the login
 * state machine forward. Implemented by [AppleLoginService]; split out so
 * the sealed challenge types don't have to import the whole service class.
 */
interface TwoFactorCoordinator {
    suspend fun requestSms(phoneNumberId: Int)
    suspend fun submitSms(phoneNumberId: Int, code: String): LoginResult
    suspend fun requestTrustedDevice()
    suspend fun submitTrustedDevice(code: String): LoginResult
}
