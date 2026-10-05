package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.apple.account.LoginResult
import io.github.tieo.taghistory.apple.account.TwoFactorChallenge
import io.github.tieo.taghistory.apple.account.TwoFactorCoordinator

/**
 * Signs the server in to Apple from a client, speaking the same
 * [LoginResult] / [TwoFactorChallenge] types the app's own sign-in produces,
 * so the shared login screen and view model drive it unchanged. Apple's
 * session stays on the server; the client only relays what the user types.
 */
class ServerLogin(private val client: ServerClient) : TwoFactorCoordinator {

    suspend fun login(email: String, password: String): LoginResult = client.login(email, password).toResult()

    override suspend fun requestSms(phoneNumberId: Int) = client.requestTwoFactor(sms(phoneNumberId))

    override suspend fun submitSms(phoneNumberId: Int, code: String): LoginResult =
        client.submitTwoFactor(sms(phoneNumberId), code).toResult()

    override suspend fun requestTrustedDevice() = client.requestTwoFactor(TRUSTED_DEVICE)

    override suspend fun submitTrustedDevice(code: String): LoginResult =
        client.submitTwoFactor(TRUSTED_DEVICE, code).toResult()

    // The server matches a method by its kind and phone number id.
    private fun sms(phoneNumberId: Int) = TwoFactorMethodDto(TwoFactorMethodDto.Kind.SMS, phoneNumberId = phoneNumberId)

    private fun LoginResponse.toResult(): LoginResult {
        if (loggedIn) return LoginResult.LoggedIn
        return LoginResult.RequireTwoFactor(
            methods.map { method ->
                when (method.kind) {
                    TwoFactorMethodDto.Kind.SMS -> {
                        TwoFactorChallenge.Sms(method.phoneNumberId ?: 0, method.phoneNumber.orEmpty(), this@ServerLogin)
                    }
                    TwoFactorMethodDto.Kind.TRUSTED_DEVICE -> TwoFactorChallenge.TrustedDevice(this@ServerLogin)
                }
            },
        )
    }

    private companion object {
        val TRUSTED_DEVICE = TwoFactorMethodDto(TwoFactorMethodDto.Kind.TRUSTED_DEVICE)
    }
}
