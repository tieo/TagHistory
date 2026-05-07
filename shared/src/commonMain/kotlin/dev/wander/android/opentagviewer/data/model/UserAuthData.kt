package io.github.tieo.taghistory.data.model

import kotlinx.serialization.Serializable

/**
 * UI-facing view of an AppleAccount snapshot — just the display-relevant
 * fields the settings screen needs. Parsed from the plaintext
 * `AppleAccount.exportToJson()` body before the full blob is encrypted to
 * disk; `ignoreUnknownKeys` matches the Jackson-backed Java port so the
 * server can grow new fields without breaking us.
 */
@Serializable
data class UserAuthData(
    val account: UserAccount? = null,
) {
    @Serializable
    data class UserAccount(
        val info: UserAccountInfo? = null,
    )

    @Serializable
    data class UserAccountInfo(
        val account_name: String? = null,
        val first_name: String? = null,
        val last_name: String? = null,
    ) {
        val accountName: String? get() = account_name
        val firstName: String? get() = first_name
        val lastName: String? get() = last_name
    }
}

/** Pair of the display-facing view + the encrypted on-disk blob. */
data class AppleUserData(
    val user: UserAuthData,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppleUserData) return false
        return user == other.user && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * user.hashCode() + data.contentHashCode()
}
