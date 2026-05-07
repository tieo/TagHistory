package io.github.tieo.taghistory.data.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM in the Android Keystore. Layout matches the old
 * `AppCryptographyUtil.flatten()` — 12-byte IV followed by ciphertext —
 * so blobs written by the Java app continue to decrypt.
 */
actual class SecureBlobStore {
    actual fun encrypt(plaintext: ByteArray, keystoreAlias: String): ByteArray {
        try {
            val key = getOrCreateKey(keystoreAlias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val cipherText = cipher.doFinal(plaintext)
            val iv = cipher.iv
            require(iv.size == IV_SIZE) {
                "Unexpected IV size ${iv.size} for $keystoreAlias"
            }
            return iv + cipherText
        } catch (t: Throwable) {
            throw SecureBlobStoreException("Failed to encrypt for $keystoreAlias", t)
        }
    }

    actual fun decrypt(envelope: ByteArray, keystoreAlias: String): ByteArray {
        try {
            require(envelope.size > IV_SIZE) { "Envelope too short for $keystoreAlias" }
            val iv = envelope.copyOfRange(0, IV_SIZE)
            val cipherText = envelope.copyOfRange(IV_SIZE, envelope.size)
            val key = getOrCreateKey(keystoreAlias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(cipherText)
        } catch (t: Throwable) {
            throw SecureBlobStoreException("Failed to decrypt for $keystoreAlias", t)
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_SIZE)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE = 256
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
