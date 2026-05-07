package io.github.tieo.taghistory.apple.crypto

private const val MSG = "iOS BigInt provider not wired up yet"

actual class BigInt : Comparable<BigInt> {
    actual operator fun plus(other: BigInt): BigInt = throw NotImplementedError(MSG)
    actual operator fun minus(other: BigInt): BigInt = throw NotImplementedError(MSG)
    actual operator fun times(other: BigInt): BigInt = throw NotImplementedError(MSG)
    actual fun mod(m: BigInt): BigInt = throw NotImplementedError(MSG)
    actual fun modPow(exponent: BigInt, m: BigInt): BigInt = throw NotImplementedError(MSG)
    actual fun signum(): Int = throw NotImplementedError(MSG)
    actual fun toMinimalBytes(): ByteArray = throw NotImplementedError(MSG)
    actual override fun compareTo(other: BigInt): Int = throw NotImplementedError(MSG)
    actual override fun equals(other: Any?): Boolean = throw NotImplementedError(MSG)
    actual override fun hashCode(): Int = throw NotImplementedError(MSG)
}

actual fun bigIntFromBytes(bytes: ByteArray): BigInt = throw NotImplementedError(MSG)
actual fun bigIntFromString(value: String, radix: Int): BigInt = throw NotImplementedError(MSG)
actual fun bigIntOf(value: Long): BigInt = throw NotImplementedError(MSG)
