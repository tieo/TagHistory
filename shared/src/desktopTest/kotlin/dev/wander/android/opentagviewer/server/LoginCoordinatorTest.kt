package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.apple.account.LoginResult
import io.github.tieo.taghistory.apple.account.TwoFactorChallenge
import io.github.tieo.taghistory.apple.account.TwoFactorCoordinator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The relay must keep Apple's stateful sign-in between a client's separate
 * requests, apply a second factor only to the sign-in that asked for it, and
 * store the account exactly once, at the end.
 */
class LoginCoordinatorTest {

    /** Stands in for Apple: SMS to phone 7 or a trusted device, code "123456". */
    private class FakeApple : TwoFactorCoordinator {
        val smsRequests = mutableListOf<Int>()
        var trustedDeviceRequests = 0

        override suspend fun requestSms(phoneNumberId: Int) { smsRequests += phoneNumberId }
        override suspend fun submitSms(phoneNumberId: Int, code: String) = check(code)
        override suspend fun requestTrustedDevice() { trustedDeviceRequests++ }
        override suspend fun submitTrustedDevice(code: String) = check(code)

        private fun check(code: String): LoginResult =
            if (code == "123456") LoginResult.LoggedIn
            else throw AppleLoginException(AppleLoginException.Kind.UNAUTHORIZED, "wrong code")

        fun challenges() = listOf(TwoFactorChallenge.Sms(7, "+49 •••• 12", this), TwoFactorChallenge.TrustedDevice(this))
    }

    private val sms7 = TwoFactorMethodDto(TwoFactorMethodDto.Kind.SMS, phoneNumberId = 7)
    private val trusted = TwoFactorMethodDto(TwoFactorMethodDto.Kind.TRUSTED_DEVICE)

    @Test
    fun second_factor_flow_stores_the_account_only_when_finished() = runBlocking<Unit> {
        val apple = FakeApple()
        var signedInAccount: AppleAccount? = null
        val stored = mutableListOf<AppleAccount>()
        val coordinator = LoginCoordinator(
            signIn = { account, _, _ ->
                signedInAccount = account
                LoginResult.RequireTwoFactor(apple.challenges())
            },
            onLoggedIn = { stored += it },
        )

        val first = coordinator.login("user@example.com", "pw")
        assertFalse(first.loggedIn)
        assertEquals(
            listOf(TwoFactorMethodDto(TwoFactorMethodDto.Kind.SMS, 7, "+49 •••• 12"), trusted),
            first.methods,
        )

        // The client echoes the method without the display number.
        coordinator.requestTwoFactor(sms7)
        assertEquals(listOf(7), apple.smsRequests)
        assertEquals(0, apple.trustedDeviceRequests)

        assertFailsWith<AppleLoginException> { coordinator.submitTwoFactor(sms7, "000000") }
        assertTrue(stored.isEmpty(), "a failed code must not store anything")

        val done = coordinator.submitTwoFactor(sms7, "123456")
        assertTrue(done.loggedIn)
        assertEquals(1, stored.size)
        assertSame(signedInAccount, stored.single(), "the stored account is the one Apple signed in")

        // The finished sign-in is gone; a replayed code has nothing to apply to.
        assertFailsWith<AppleLoginException> { coordinator.submitTwoFactor(sms7, "123456") }
        assertEquals(1, stored.size)
    }

    @Test
    fun a_method_apple_did_not_offer_is_refused() = runBlocking<Unit> {
        val apple = FakeApple()
        val coordinator = LoginCoordinator(
            signIn = { _, _, _ -> LoginResult.RequireTwoFactor(apple.challenges()) },
            onLoggedIn = {},
        )
        coordinator.login("user@example.com", "pw")

        assertFailsWith<AppleLoginException> {
            coordinator.requestTwoFactor(TwoFactorMethodDto(TwoFactorMethodDto.Kind.SMS, phoneNumberId = 8))
        }
        assertTrue(apple.smsRequests.isEmpty())
    }

    @Test
    fun a_new_sign_in_replaces_an_unfinished_one() = runBlocking<Unit> {
        val first = FakeApple()
        val second = FakeApple()
        val fakes = ArrayDeque(listOf(first, second))
        val coordinator = LoginCoordinator(
            signIn = { _, _, _ -> LoginResult.RequireTwoFactor(fakes.removeFirst().challenges()) },
            onLoggedIn = {},
        )
        coordinator.login("user@example.com", "pw")
        coordinator.login("user@example.com", "pw")

        coordinator.requestTwoFactor(trusted)

        assertEquals(0, first.trustedDeviceRequests, "the abandoned sign-in must not be driven")
        assertEquals(1, second.trustedDeviceRequests)
    }

    @Test
    fun sign_in_without_second_factor_stores_at_once() = runBlocking<Unit> {
        var stored: AppleAccount? = null
        val coordinator = LoginCoordinator(signIn = { _, _, _ -> LoginResult.LoggedIn }, onLoggedIn = { stored = it })

        assertTrue(coordinator.login("user@example.com", "pw").loggedIn)
        assertNotNull(stored)
        assertFailsWith<AppleLoginException> { coordinator.requestTwoFactor(trusted) }
    }

    @Test
    fun a_rejected_password_stores_nothing() = runBlocking<Unit> {
        var stored: AppleAccount? = null
        val coordinator = LoginCoordinator(
            signIn = { _, _, _ -> throw AppleLoginException(AppleLoginException.Kind.INVALID_CREDENTIALS, "bad") },
            onLoggedIn = { stored = it },
        )
        assertFailsWith<AppleLoginException> { coordinator.login("user@example.com", "wrong") }
        assertNull(stored)
    }
}
