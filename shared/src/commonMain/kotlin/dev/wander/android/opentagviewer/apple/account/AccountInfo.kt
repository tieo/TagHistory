package io.github.tieo.taghistory.apple.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Info populated after SRP authentication, extracted from the SPD plist
 * in the GSA response. Mirrors the `_AccountInfo` TypedDict in
 * `findmy/reports/account.py`.
 *
 * [trustedDevice2fa] flips to `true` only when GSA answers with
 * `trustedDeviceSecondaryAuth` — that tells the 2FA picker it should
 * surface the "trusted device" challenge alongside SMS.
 */
@Serializable
data class AccountInfo(
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("trusted_device_2fa") var trustedDevice2fa: Boolean = false,
)
