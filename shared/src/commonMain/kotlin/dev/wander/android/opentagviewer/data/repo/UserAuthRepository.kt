package io.github.tieo.taghistory.data.repo

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.data.model.AppleUserData
import io.github.tieo.taghistory.data.model.UserAuthData
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.sync.SyncEvent
import io.github.tieo.taghistory.sync.SyncLog
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

    /**
     * The stored credential, or null when there is none or it cannot be read
     * (corrupt Base64, a keystore key that is gone or failing, unparsable
     * JSON). Every startup path asks this to decide whether the user is signed
     * in, so throwing here crashed the app on every launch until its data was
     * cleared. An unreadable blob reads as signed out and is left in place: a
     * keystore failure can be transient, and signing in again overwrites it.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun getUserAuth(): AppleUserData? {
        val encoded = settings.getStringOrNull(KEY_APPLE_ACCOUNT) ?: return null
        return try {
            val envelope = Base64.decode(encoded)
            val plaintext = crypto.decrypt(envelope, keystoreAlias)
            val header = json.decodeFromString(UserAuthData.serializer(), plaintext.decodeToString())
            AppleUserData(user = header, data = envelope)
        } catch (e: Exception) {
            SyncLog.record(
                SyncEvent.Kind.INFO,
                "Stored Apple credentials could not be read, treating as signed out: ${e::class.simpleName}",
            )
            null
        }
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
