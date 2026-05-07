package io.github.tieo.taghistory.data.storage

/**
 * Platform-specific keystore-backed blob envelope. The Java side used
 * Android Keystore AES-GCM with a flattened `IV || ciphertext` layout — the
 * Android actual preserves that exact format so existing on-disk blobs
 * still decrypt after the KMP migration. Desktop/iOS actuals are stubs for
 * now; those hosts don't ship login today.
 */
expect class SecureBlobStore {
    /** Returns the flattened `IV || ciphertext` envelope. */
    fun encrypt(plaintext: ByteArray, keystoreAlias: String): ByteArray

    /** Accepts the same flattened envelope produced by `encrypt`. */
    fun decrypt(envelope: ByteArray, keystoreAlias: String): ByteArray
}

class SecureBlobStoreException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
