package io.github.tieo.taghistory.apple.findmy

import io.github.tieo.taghistory.apple.crypto.bigIntFromBytes
import io.github.tieo.taghistory.apple.crypto.sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NIST P-224 keypair matching `findmy/keys.py#KeyPair`.
 *
 * Only the private scalar is stored; the public X coordinate is derived
 * eagerly via [P224.derivePublicX] and cached. Equality + hashCode are
 * based on the public advertisement key so duplicates across the primary
 * and secondary generators dedupe correctly in a Set.
 */
@OptIn(ExperimentalEncodingApi::class)
class KeyPair(
    privateKey: ByteArray,
    val keyType: KeyType = KeyType.UNKNOWN,
    var name: String? = null,
) {
    private val privateKey: ByteArray
    private val adv: ByteArray

    init {
        require(privateKey.size == PRIVATE_KEY_LENGTH) {
            "P-224 private key must be $PRIVATE_KEY_LENGTH bytes, got ${privateKey.size}"
        }
        this.privateKey = privateKey.copyOf()
        this.adv = P224.derivePublicX(this.privateKey)
    }

    fun privateKeyBytes(): ByteArray = privateKey.copyOf()
    fun advKeyBytes(): ByteArray = adv.copyOf()
    fun hashedAdvKeyBytes(): ByteArray = sha256(adv)

    fun privateKeyB64(): String = Base64.encode(privateKey)
    fun advKeyB64(): String = Base64.encode(adv)
    fun hashedAdvKeyB64(): String = Base64.encode(hashedAdvKeyBytes())

    /**
     * P-224 ECDH — returns the X coordinate of `privateKey · otherPub` as
     * a 28-byte big-endian scalar. Matches the Python reference's
     * `EllipticCurvePrivateKey.exchange(ECDH(), other)`.
     */
    fun dhExchange(otherPubEncoded: ByteArray): ByteArray =
        P224.dhExchangeX(privateKey, otherPubEncoded)

    override fun equals(other: Any?): Boolean =
        other is KeyPair && adv.contentEquals(other.adv)

    override fun hashCode(): Int = adv.contentHashCode()

    override fun toString(): String =
        "KeyPair(name=$name, pub=${advKeyB64()}, type=$keyType)"

    companion object {
        /** P-224 scalar size in bytes. */
        const val PRIVATE_KEY_LENGTH: Int = 28

        fun fromB64(b64: String): KeyPair = KeyPair(Base64.decode(b64))

        /**
         * Minimum-length big-endian unsigned encoding, fixed to [length].
         * Shared with [AccessoryKeyGenerator] via top-level fn so callers
         * don't have to reach into internals.
         */
        internal fun toFixedLength(value: io.github.tieo.taghistory.apple.crypto.BigInt, length: Int): ByteArray {
            val raw = value.toMinimalBytes()
            return when {
                raw.size == length -> raw
                raw.size < length -> ByteArray(length).also {
                    raw.copyInto(it, destinationOffset = length - raw.size)
                }
                else -> throw IllegalArgumentException(
                    "value does not fit in $length bytes (got ${raw.size})"
                )
            }
        }

        /** Helper so tests don't need to construct a BigInt themselves. */
        internal fun bigIntFromUnsignedBytes(bytes: ByteArray) = bigIntFromBytes(bytes)
    }
}
