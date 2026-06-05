package io.github.tieo.taghistory.apple.crypto

private fun NI(): Nothing = throw NotImplementedError("Apple crypto stack not available on wasmJs")

actual class BigInt : Comparable<BigInt> {
    actual operator fun plus(other: BigInt): BigInt = NI()
    actual operator fun minus(other: BigInt): BigInt = NI()
    actual operator fun times(other: BigInt): BigInt = NI()
    actual fun mod(m: BigInt): BigInt = NI()
    actual fun modPow(exponent: BigInt, m: BigInt): BigInt = NI()
    actual fun signum(): Int = NI()
    actual fun toMinimalBytes(): ByteArray = NI()
    actual override fun compareTo(other: BigInt): Int = NI()
    actual override fun equals(other: Any?): Boolean = NI()
    actual override fun hashCode(): Int = NI()
}

actual fun bigIntFromBytes(bytes: ByteArray): BigInt = NI()
actual fun bigIntFromString(value: String, radix: Int): BigInt = NI()
actual fun bigIntOf(value: Long): BigInt = NI()

actual fun sha256(data: ByteArray): ByteArray = NI()
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = NI()
actual fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    dkLenBytes: Int,
): ByteArray = NI()
actual fun aesCbcDecryptPkcs7(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray = NI()
actual fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertextWithTag: ByteArray,
    tagLenBits: Int,
): ByteArray = NI()

actual fun secureRng(): Rng = Rng { _ -> NI() }
