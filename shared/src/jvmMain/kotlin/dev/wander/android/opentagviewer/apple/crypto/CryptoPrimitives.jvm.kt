package io.github.tieo.taghistory.apple.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

actual fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    dkLenBytes: Int,
): ByteArray {
    val prf = Mac.getInstance("HmacSHA256")
    prf.init(SecretKeySpec(password, "HmacSHA256"))
    val hLen = prf.macLength
    val blocks = (dkLenBytes + hLen - 1) / hLen
    val out = ByteArray(blocks * hLen)

    for (i in 1..blocks) {
        prf.reset()
        prf.update(salt)
        prf.update(intBigEndian(i))
        var u = prf.doFinal()
        val t = u.copyOf()
        for (j in 1 until iterations) {
            prf.reset()
            u = prf.doFinal(u)
            for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
        }
        t.copyInto(out, destinationOffset = (i - 1) * hLen)
    }
    return if (out.size == dkLenBytes) out else out.copyOf(dkLenBytes)
}

actual fun aesCbcDecryptPkcs7(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(ciphertext)
}

actual fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertextWithTag: ByteArray,
    tagLenBits: Int,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        GCMParameterSpec(tagLenBits, iv),
    )
    return cipher.doFinal(ciphertextWithTag)
}

private fun intBigEndian(value: Int): ByteArray = byteArrayOf(
    ((value ushr 24) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)
