package io.github.tieo.taghistory.data.storage

/**
 * Web preview secure store. Browsers have no platform keystore,
 * and binding a hidden-tab WebCrypto key to localStorage offers no
 * meaningful security against an attacker that already runs in
 * page context. To keep the auth flow boot-friendly on web we
 * accept blobs as-is — the envelope is just `[0x00] || plaintext`
 * so decrypt is non-throwing on a freshly written blob.
 *
 * This is *not* secure. Surfaces backed by it should be gated
 * behind a "web preview" feature flag before any real key
 * material is ever written.
 */
actual class SecureBlobStore {
    actual fun encrypt(plaintext: ByteArray, keystoreAlias: String): ByteArray =
        ByteArray(plaintext.size + 1).also { out ->
            out[0] = WEB_TAG
            plaintext.copyInto(out, 1)
        }

    actual fun decrypt(envelope: ByteArray, keystoreAlias: String): ByteArray {
        if (envelope.isEmpty() || envelope[0] != WEB_TAG) {
            throw SecureBlobStoreException(
                "SecureBlobStore envelope was not written by the wasm pass-through stub"
            )
        }
        return envelope.copyOfRange(1, envelope.size)
    }

    private companion object {
        const val WEB_TAG: Byte = 0x00
    }
}
