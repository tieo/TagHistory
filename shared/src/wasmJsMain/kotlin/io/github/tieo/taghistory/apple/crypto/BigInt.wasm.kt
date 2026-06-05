package io.github.tieo.taghistory.apple.crypto

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign

/**
 * Wasm actual for the SRP / ECDH BigInt expect. Forwards every op to
 * ionspin's pure-Kotlin BigInteger so the math is identical across
 * targets — the JVM actual uses java.math.BigInteger, this one uses
 * ionspin, but both produce the same minimum-length unsigned bytes
 * (verified by the round-trip + modPow tests).
 */
actual class BigInt internal constructor(internal val value: BigInteger) : Comparable<BigInt> {

    actual operator fun plus(other: BigInt): BigInt = BigInt(value + other.value)
    actual operator fun minus(other: BigInt): BigInt = BigInt(value - other.value)
    actual operator fun times(other: BigInt): BigInt = BigInt(value * other.value)
    actual fun mod(m: BigInt): BigInt = BigInt(value.mod(m.value))
    actual fun modPow(exponent: BigInt, m: BigInt): BigInt {
        // ionspin BigInteger has no native modPow, so square-and-multiply
        // by walking the binary representation of the exponent. Reducing
        // mod `m` after every multiply keeps intermediates bounded.
        var result = BigInteger.ONE
        var base = value.mod(m.value)
        var exp = exponent.value
        while (exp > BigInteger.ZERO) {
            if (exp and BigInteger.ONE == BigInteger.ONE) {
                result = (result * base).mod(m.value)
            }
            exp = exp shr 1
            if (exp > BigInteger.ZERO) base = (base * base).mod(m.value)
        }
        return BigInt(result)
    }

    actual fun signum(): Int = when (value.getSign()) {
        Sign.POSITIVE -> 1
        Sign.NEGATIVE -> -1
        Sign.ZERO -> 0
    }

    actual fun toMinimalBytes(): ByteArray {
        if (value.isZero()) return ByteArray(0)
        // ionspin returns big-endian magnitude bytes; strip any leading
        // zero so output matches java.math.BigInteger.toByteArray with
        // the sign byte stripped (the pysrp convention GSA expects).
        val bytes = value.toByteArray()
        var first = 0
        while (first < bytes.size - 1 && bytes[first].toInt() == 0) first++
        return bytes.copyOfRange(first, bytes.size)
    }

    actual override fun compareTo(other: BigInt): Int = value.compareTo(other.value)
    actual override fun equals(other: Any?): Boolean =
        other is BigInt && value == other.value
    actual override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString(10)
}

actual fun bigIntFromBytes(bytes: ByteArray): BigInt {
    if (bytes.isEmpty()) return BigInt(BigInteger.ZERO)
    return BigInt(BigInteger.fromByteArray(bytes, Sign.POSITIVE))
}

actual fun bigIntFromString(value: String, radix: Int): BigInt =
    BigInt(BigInteger.parseString(value, radix))

actual fun bigIntOf(value: Long): BigInt = BigInt(BigInteger.fromLong(value))
