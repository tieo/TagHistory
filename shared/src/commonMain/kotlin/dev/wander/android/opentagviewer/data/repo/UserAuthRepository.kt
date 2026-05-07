package io.github.tieo.taghistory.data.repo

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.data.model.AppleUserData
import io.github.tieo.taghistory.data.model.UserAuthData
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json

/**
 * On-disk Apple-account blob + UI-facing header info. The underlying
 * store is still the keystore envelope the Java app produced; on
 * platforms whose `SecureBlobStore` is a pass-through (desktop/iOS
 * stubs) the blob is stored unencrypted until real keystore impls land.
 *
 * Blob is base64 in the `Settings` string — multiplatform-settings has
 * no native ByteArray support, and base64 makes the stored form visible
 * in debuggers / prefs editors without extra tooling.
 */
class UserAuthRepository(
    private val settings: Settings,
    private val crypto: SecureBlobStore,
    private val keystoreAlias: String,
    private val json: Json = DefaultJson,
) {

    @OptIn(ExperimentalEncodingApi::class)
    fun getUserAuth(): AppleUserData? {
        val encoded = settings.getStringOrNull(KEY_APPLE_ACCOUNT) ?: return null
        val envelope = Base64.decode(encoded)
        val plaintext = crypto.decrypt(envelope, keystoreAlias)
        val header = json.decodeFromString(UserAuthData.serializer(), plaintext.decodeToString())
        return AppleUserData(user = header, data = envelope)
    }

    fun clearUser() {
        settings.remove(KEY_APPLE_ACCOUNT)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun storeUserAuth(plaintext: ByteArray) {
        val envelope = crypto.encrypt(plaintext, keystoreAlias)
        settings.putString(KEY_APPLE_ACCOUNT, Base64.encode(envelope))
    }

    fun decrypt(envelope: ByteArray): ByteArray = crypto.decrypt(envelope, keystoreAlias)

    private companion object {
        const val KEY_APPLE_ACCOUNT = "apple_account"
        val DefaultJson = Json { ignoreUnknownKeys = true }
    }
}
