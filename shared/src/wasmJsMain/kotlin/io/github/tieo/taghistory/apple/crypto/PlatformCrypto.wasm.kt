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
/**
 * AES-128 CBC decrypt with PKCS#7 unpadding. Built on top of the
 * raw-block AES from `kotlinx-crypto-aes`; that lib doesn't ship
 * mode wrappers so CBC is implemented in-tree.
 */
actual fun aesCbcDecryptPkcs7(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    require(ciphertext.size % 16 == 0) { "CBC ciphertext must be a multiple of 16 bytes" }
    require(iv.size == 16) { "CBC IV must be 16 bytes" }
    val cipher = io.github.andreypfau.kotlinx.crypto.AES(key)
    val out = ByteArray(ciphertext.size)
    val block = ByteArray(16)
    var prev = iv
    var offset = 0
    while (offset < ciphertext.size) {
        cipher.decryptBlock(ciphertext, block, 0, offset)
        for (i in 0 until 16) out[offset + i] = (block[i].toInt() xor prev[i].toInt()).toByte()
        prev = ciphertext.copyOfRange(offset, offset + 16)
        offset += 16
    }
    val padLen = out.last().toInt() and 0xFF
    require(padLen in 1..16) { "Invalid PKCS#7 padding length $padLen" }
    for (i in out.size - padLen until out.size) {
        require((out[i].toInt() and 0xFF) == padLen) { "Invalid PKCS#7 padding byte" }
    }
    return out.copyOfRange(0, out.size - padLen)
}

/**
 * AES-128-GCM decrypt. [ciphertextWithTag] is `ciphertext || tag`
 * (JCA layout). CTR mode for stream + GHASH for authentication —
 * both in-tree because the upstream block-cipher lib doesn't ship
 * an AEAD wrapper.
 */
actual fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertextWithTag: ByteArray,
    tagLenBits: Int,
): ByteArray {
    val tagLen = tagLenBits / 8
    require(tagLen in 4..16) { "GCM tag length $tagLenBits bits is out of range" }
    require(ciphertextWithTag.size >= tagLen) { "Ciphertext shorter than tag" }
    val ctLen = ciphertextWithTag.size - tagLen
    val ct = ciphertextWithTag.copyOfRange(0, ctLen)
    val tag = ciphertextWithTag.copyOfRange(ctLen, ciphertextWithTag.size)

    val cipher = io.github.andreypfau.kotlinx.crypto.AES(key)

    // J0 = IV || 0x00000001 when IV is 96 bits; otherwise GHASH(IV).
    val j0 = ByteArray(16)
    if (iv.size == 12) {
        iv.copyInto(j0, 0, 0, 12)
        j0[15] = 0x01
    } else {
        val ivLenBits = iv.size * 8L
        val padded = ByteArray(((iv.size + 15) / 16) * 16 + 16)
        iv.copyInto(padded, 0, 0, iv.size)
        for (k in 0 until 8) padded[padded.size - 1 - k] = (ivLenBits ushr (8 * k)).toByte()
        val h = ByteArray(16).also { cipher.encryptBlock(it, it, 0, 0) }
        var y = ByteArray(16)
        var off = 0
        while (off < padded.size) {
            for (i in 0 until 16) y[i] = (y[i].toInt() xor padded[off + i].toInt()).toByte()
            y = ghashMul(y, h)
            off += 16
        }
        y.copyInto(j0, 0, 0, 16)
    }

    // Authenticate ciphertext (no AAD).
    val h = ByteArray(16).also { cipher.encryptBlock(it, it, 0, 0) }
    var ghash = ByteArray(16)
    var off = 0
    while (off < ct.size) {
        for (i in 0 until 16) {
            ghash[i] = (
                ghash[i].toInt() xor
                    if (off + i < ct.size) ct[off + i].toInt() else 0
                ).toByte()
        }
        ghash = ghashMul(ghash, h)
        off += 16
    }
    val lenBlock = ByteArray(16)
    val ctLenBits = ct.size.toLong() * 8L
    for (k in 0 until 8) lenBlock[15 - k] = (ctLenBits ushr (8 * k)).toByte()
    for (i in 0 until 16) ghash[i] = (ghash[i].toInt() xor lenBlock[i].toInt()).toByte()
    ghash = ghashMul(ghash, h)
    val s0 = ByteArray(16).also { cipher.encryptBlock(j0, it, 0, 0) }
    val computedTag = ByteArray(16) { (ghash[it].toInt() xor s0[it].toInt()).toByte() }

    var diff = 0
    for (i in 0 until tagLen) diff = diff or (computedTag[i].toInt() xor tag[i].toInt())
    require(diff == 0) { "AES-GCM tag mismatch" }

    // Decrypt via CTR starting at inc32(J0).
    val counter = j0.copyOf()
    inc32(counter)
    val out = ByteArray(ctLen)
    val ks = ByteArray(16)
    var idx = 0
    while (idx < ctLen) {
        cipher.encryptBlock(counter, ks, 0, 0)
        val take = minOf(16, ctLen - idx)
        for (i in 0 until take) out[idx + i] = (ct[idx + i].toInt() xor ks[i].toInt()).toByte()
        inc32(counter)
        idx += 16
    }
    return out
}

private fun inc32(counter: ByteArray) {
    var i = 15
    while (i >= 12) {
        val v = (counter[i].toInt() and 0xFF) + 1
        counter[i] = v.toByte()
        if (v < 256) return
        i--
    }
}

/** GHASH multiplication in GF(2^128). */
private fun ghashMul(x: ByteArray, y: ByteArray): ByteArray {
    val z = ByteArray(16)
    val v = y.copyOf()
    for (i in 0 until 128) {
        val bit = (x[i / 8].toInt() ushr (7 - (i % 8))) and 1
        if (bit == 1) for (k in 0 until 16) z[k] = (z[k].toInt() xor v[k].toInt()).toByte()
        val lsb = v[15].toInt() and 1
        for (k in 15 downTo 1) {
            v[k] = ((v[k].toInt() and 0xFF) ushr 1 or ((v[k - 1].toInt() and 1) shl 7)).toByte()
        }
        v[0] = ((v[0].toInt() and 0xFF) ushr 1).toByte()
        if (lsb == 1) v[0] = (v[0].toInt() xor 0xE1.toInt().toByte().toInt()).toByte()
    }
    return z
}

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
