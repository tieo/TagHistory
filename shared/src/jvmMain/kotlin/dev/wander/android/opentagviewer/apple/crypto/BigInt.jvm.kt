package io.github.tieo.taghistory.apple.crypto

import java.math.BigInteger

actual class BigInt internal constructor(internal val value: BigInteger) : Comparable<BigInt> {
    actual operator fun plus(other: BigInt): BigInt = BigInt(value.add(other.value))
    actual operator fun minus(other: BigInt): BigInt = BigInt(value.subtract(other.value))
    actual operator fun times(other: BigInt): BigInt = BigInt(value.multiply(other.value))
    actual fun mod(m: BigInt): BigInt = BigInt(value.mod(m.value))
    actual fun modPow(exponent: BigInt, m: BigInt): BigInt =
        BigInt(value.modPow(exponent.value, m.value))

    actual fun signum(): Int = value.signum()

    actual fun toMinimalBytes(): ByteArray {
        require(value.signum() >= 0) { "Cannot encode negative BigInt" }
        val raw = value.toByteArray()
        // java.math.BigInteger.toByteArray prepends a 0x00 byte when the
        // high bit is set so the two's-complement representation is
        // non-negative. pysrp's long_to_bytes does not. Strip the leading
        // zero so wire encoding matches Python.
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    actual override fun compareTo(other: BigInt): Int = value.compareTo(other.value)
    actual override fun equals(other: Any?): Boolean = other is BigInt && value == other.value
    actual override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value.toString()
}

actual fun bigIntFromBytes(bytes: ByteArray): BigInt = BigInt(BigInteger(1, bytes))
actual fun bigIntFromString(value: String, radix: Int): BigInt = BigInt(BigInteger(value, radix))
actual fun bigIntOf(value: Long): BigInt = BigInt(BigInteger.valueOf(value))
