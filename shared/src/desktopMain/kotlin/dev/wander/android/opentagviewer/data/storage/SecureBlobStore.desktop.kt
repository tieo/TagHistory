package io.github.tieo.taghistory.data.storage

/**
 * Desktop stub. Production login on desktop is a later phase — until
 * there's a keystore-equivalent wired up we pass blobs through unchanged
 * so tests can exercise the repository surface without platform crypto.
 */
actual class SecureBlobStore {
    actual fun encrypt(plaintext: ByteArray, keystoreAlias: String): ByteArray = plaintext
    actual fun decrypt(envelope: ByteArray, keystoreAlias: String): ByteArray = envelope
}
