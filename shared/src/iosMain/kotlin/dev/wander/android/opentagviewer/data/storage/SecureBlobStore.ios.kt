package io.github.tieo.taghistory.data.storage

/**
 * iOS stub. Real implementation will wrap Keychain/Secure Enclave once
 * iOS login is wired up. Until then, pass-through keeps the expect/actual
 * symmetry so the shared module compiles for the iOS targets.
 */
actual class SecureBlobStore {
    actual fun encrypt(plaintext: ByteArray, keystoreAlias: String): ByteArray = plaintext
    actual fun decrypt(envelope: ByteArray, keystoreAlias: String): ByteArray = envelope
}
