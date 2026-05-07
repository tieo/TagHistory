package io.github.tieo.taghistory.apple.findmy

import io.github.tieo.taghistory.apple.crypto.BigInt
import io.github.tieo.taghistory.apple.crypto.GsaCrypto
import io.github.tieo.taghistory.apple.crypto.bigIntFromBytes
import io.github.tieo.taghistory.apple.crypto.bigIntFromString
import io.github.tieo.taghistory.apple.crypto.bigIntOf

/**
 * Mirrors `findmy/accessory.py#AccessoryKeyGenerator`: rolls the
 * symmetric secret forward with X9.63-KDF and derives per-index P-224
 * private scalars via the "diversify" branch of the same KDF.
 *
 * Keeps an internal cursor so consecutive forward lookups are O(1).
 * Rewinds restart from the initial SK.
 */
class AccessoryKeyGenerator(
    masterKey: ByteArray,
    initialSk: ByteArray,
    val keyType: KeyType,
) {
    private val masterKey: ByteArray
    private val initialSk: ByteArray

    private var currentSk: ByteArray
    private var currentIndex: Int = 0

    /**
     * Memoised keyAt results. Keys are deterministic per (accessory,
     * index) so refresh cycles (cascade + periodic ticks) hit cache on
     * every previously-seen slot. 96 indices × 7 accessories ≈ 70 KB —
     * negligible. Cuts SHA-256 work on refresh #2+ to zero new slots.
     */
    private val keyCache: MutableMap<Int, KeyPair> = HashMap()

    init {
        require(masterKey.size == MASTER_KEY_LENGTH) {
            "Master key must be $MASTER_KEY_LENGTH bytes, got ${masterKey.size}"
        }
        require(initialSk.size == SECRET_KEY_LENGTH) {
            "Initial SK must be $SECRET_KEY_LENGTH bytes, got ${initialSk.size}"
        }
        this.masterKey = masterKey.copyOf()
        this.initialSk = initialSk.copyOf()
        this.currentSk = initialSk.copyOf()
    }

    fun keyAt(index: Int): KeyPair {
        require(index >= 0) { "Key index must be non-negative, got $index" }
        keyCache[index]?.let { return it }
        val sk = secretKeyAt(index)
        val priv = derivePsKey(masterKey, sk)
        val pair = KeyPair(priv, keyType)
        keyCache[index] = pair
        return pair
    }

    internal fun secretKeyAt(index: Int): ByteArray {
        if (index < currentIndex) {
            currentSk = initialSk.copyOf()
            currentIndex = 0
        }
        while (currentIndex < index) {
            currentSk = GsaCrypto.x963Kdf(currentSk, UPDATE, 32)
            currentIndex++
        }
        return currentSk.copyOf()
    }

    companion object {
        const val MASTER_KEY_LENGTH: Int = 28
        const val SECRET_KEY_LENGTH: Int = 32

        private val UPDATE: ByteArray = "update".encodeToByteArray()
        private val DIVERSIFY: ByteArray = "diversify".encodeToByteArray()

        // P-224 group order minus one — reused in derivePsKey.
        private val P224_N: BigInt = bigIntFromString(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFF16A2E0B8F03E13DD29455C5C2A3D",
            radix = 16,
        )
        private val P224_N_MINUS_ONE: BigInt = P224_N - bigIntOf(1L)

        /**
         * Mirrors `findmy/util/crypto.py#derive_ps_key`. Returns the 28-byte
         * big-endian private scalar used as input to [KeyPair].
         */
        fun derivePsKey(masterKey: ByteArray, sk: ByteArray): ByteArray {
            val at = GsaCrypto.x963Kdf(sk, DIVERSIFY, 72)
            val u = bigIntFromBytes(at.copyOfRange(0, 36)).mod(P224_N_MINUS_ONE) + bigIntOf(1L)
            val v = bigIntFromBytes(at.copyOfRange(36, 72)).mod(P224_N_MINUS_ONE) + bigIntOf(1L)
            val priv = (u * bigIntFromBytes(masterKey) + v).mod(P224_N)
            return KeyPair.toFixedLength(priv, KeyPair.PRIVATE_KEY_LENGTH)
        }
    }
}
