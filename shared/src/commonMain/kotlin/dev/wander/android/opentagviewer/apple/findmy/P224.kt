package io.github.tieo.taghistory.apple.findmy

/**
 * NIST P-224 point operations used by FindMy.
 *
 * Factored behind `expect` because there is no pure-Kotlin P-224
 * implementation we can reuse across KMP targets. On JVM/Android we
 * delegate to BouncyCastle's curve arithmetic; iOS will throw until
 * Phase 15 wires up Swift/CryptoKit.
 *
 * All byte arrays are big-endian, fixed length:
 *  - private scalar: 28 bytes
 *  - public point (uncompressed): 57 bytes (`0x04 || X || Y`)
 *  - returned X-only values: 28 bytes
 */
expect object P224 {

    /**
     * Derive the X coordinate of `privateKey · G` and return it as a
     * 28-byte big-endian scalar. Matches `findmy/keys.py#_derive_pub`.
     */
    fun derivePublicX(privateKey: ByteArray): ByteArray

    /**
     * P-224 ECDH: returns the X coordinate of `privateKey · otherPub`.
     * `otherPubEncoded` may be either uncompressed (57 bytes) or compressed
     * (29 bytes). Output is a 28-byte big-endian scalar.
     */
    fun dhExchangeX(privateKey: ByteArray, otherPubEncoded: ByteArray): ByteArray
}
