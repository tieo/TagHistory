package io.github.tieo.taghistory.data.storage

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM blob envelope. With a key file it is AES-256-GCM in the same flattened
 * `IV || ciphertext` layout the Android Keystore actual writes; the key file
 * holds 32 bytes, raw or Base64 encoded, and lives apart from the data
 * directory (the server reads it from a secrets mount), so a copy of the data
 * alone does not reveal the Apple session.
 *
 * Without a key file the blobs pass through unchanged. That is the desktop
 * dev harness and the tests; the server refuses to start without a key.
 */
actual class SecureBlobStore(private val keyFile: File? = null) {

    private val key: SecretKeySpec? by lazy {
        keyFile?.let { file ->
            val raw = file.readBytes()
            val bytes = if (raw.size == KEY_BYTES) {
                raw
            } else {
                runCatching { java.util.Base64.getDecoder().decode(raw.decodeToString().trim()) }.getOrDefault(raw)
            }
            if (bytes.size != KEY_BYTES) {
                throw SecureBlobStoreException("Key file $file must hold $KEY_BYTES bytes, has ${bytes.size}")
            }
            SecretKeySpec(bytes, "AES")
        }
    }

    actual fun encrypt(plaintext: ByteArray, keystoreAlias: String): ByteArray {
        val k = key ?: return plaintext
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(keystoreAlias.encodeToByteArray())
        return iv + cipher.doFinal(plaintext)
    }

    actual fun decrypt(envelope: ByteArray, keystoreAlias: String): ByteArray {
        val k = key ?: return envelope
        if (envelope.size <= IV_BYTES) throw SecureBlobStoreException("Envelope too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, envelope, 0, IV_BYTES))
        cipher.updateAAD(keystoreAlias.encodeToByteArray())
        return try {
            cipher.doFinal(envelope, IV_BYTES, envelope.size - IV_BYTES)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecureBlobStoreException("Blob does not decrypt with this key", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
