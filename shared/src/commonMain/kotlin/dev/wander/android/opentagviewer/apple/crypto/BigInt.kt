package io.github.tieo.taghistory.apple.crypto

/**
 * Thin KMP abstraction over an arbitrary-precision unsigned integer,
 * implemented via `java.math.BigInteger` on JVM and `throw`-on-use on
 * iOS (not a ship target yet).
 *
 * Only the operations SRP-6a needs are exposed — modPow, add, subtract,
 * multiply, mod, toByteArray (minimum-length unsigned big-endian),
 * signum. Matches pysrp's `long_to_bytes` semantics (no leading zero
 * padding) which GSA's server is sensitive to.
 */
expect class BigInt : Comparable<BigInt> {
    operator fun plus(other: BigInt): BigInt
    operator fun minus(other: BigInt): BigInt
    operator fun times(other: BigInt): BigInt
    fun mod(m: BigInt): BigInt
    fun modPow(exponent: BigInt, m: BigInt): BigInt
    fun signum(): Int

    /** Unpadded big-endian byte representation (no leading zero). */
    fun toMinimalBytes(): ByteArray

    override fun compareTo(other: BigInt): Int
    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
}

/** Parse an unsigned big-endian byte array. */
expect fun bigIntFromBytes(bytes: ByteArray): BigInt

/** Parse a base-10 or base-16 literal. */
expect fun bigIntFromString(value: String, radix: Int = 10): BigInt

expect fun bigIntOf(value: Long): BigInt
