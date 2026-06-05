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

actual fun sha256(data: ByteArray): ByteArray =
    org.kotlincrypto.hash.sha2.SHA256().digest(data)

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    org.kotlincrypto.macs.hmac.sha2.HmacSHA256(key).doFinal(data)

/**
 * RFC-2898 PBKDF2-HMAC-SHA-256. Same byte-level contract as the JVM
 * actual — built directly on top of the KotlinCrypto HMAC primitive
 * so we don't need a separate KDF dep that lacks a wasmJs artifact.
 */
actual fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    dkLenBytes: Int,
): ByteArray {
    val mac = org.kotlincrypto.macs.hmac.sha2.HmacSHA256(password)
    val hLen = 32 // SHA-256 output size
    val blocks = (dkLenBytes + hLen - 1) / hLen
    val out = ByteArray(dkLenBytes)
    for (i in 1..blocks) {
        val intBlock = byteArrayOf(
            (i ushr 24).toByte(),
            (i ushr 16).toByte(),
            (i ushr 8).toByte(),
            i.toByte(),
        )
        var u = mac.doFinal(salt + intBlock)
        val t = u.copyOf()
        for (round in 2..iterations) {
            u = mac.doFinal(u)
            for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        val offset = (i - 1) * hLen
        val len = minOf(hLen, dkLenBytes - offset)
        t.copyInto(out, offset, 0, len)
    }
    return out
}
actual fun aesCbcDecryptPkcs7(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray = NI()
actual fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertextWithTag: ByteArray,
    tagLenBits: Int,
): ByteArray = NI()

// Browser's crypto.getRandomValues is synchronous, so it fits the
// existing Rng contract without bouncing through a coroutine. Returns
// the bytes as a string of code points (one byte per char, 0..255)
// because Kotlin/Wasm's JS interop can't hand a Uint8Array back to
// Kotlin directly — string round-trips cleanly through interop.
private fun randomBytesString(len: Int): String =
    js("(() => { var a = new Uint8Array(len); crypto.getRandomValues(a); var s = ''; for (var i = 0; i < a.length; i++) s += String.fromCharCode(a[i]); return s; })()")

actual fun secureRng(): Rng = Rng { out ->
    val s = randomBytesString(out.size)
    for (i in out.indices) out[i] = s[i].code.toByte()
}
