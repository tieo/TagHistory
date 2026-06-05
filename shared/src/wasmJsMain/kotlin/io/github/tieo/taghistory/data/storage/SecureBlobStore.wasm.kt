package io.github.tieo.taghistory.data.storage

private fun NI(): Nothing = throw SecureBlobStoreException("Secure storage not available on wasmJs")

actual class SecureBlobStore {
    actual fun encrypt(plaintext: ByteArray, keystoreAlias: String): ByteArray = NI()
    actual fun decrypt(envelope: ByteArray, keystoreAlias: String): ByteArray = NI()
}
