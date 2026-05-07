package io.github.tieo.taghistory.apple.crypto

private const val MSG = "iOS crypto provider not wired up yet"

actual fun sha256(data: ByteArray): ByteArray = throw NotImplementedError(MSG)
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = throw NotImplementedError(MSG)
actual fun pbkdf2HmacSha256(
    password: ByteArray, salt: ByteArray, iterations: Int, dkLenBytes: Int
): ByteArray = throw NotImplementedError(MSG)
actual fun aesCbcDecryptPkcs7(
    key: ByteArray, iv: ByteArray, ciphertext: ByteArray
): ByteArray = throw NotImplementedError(MSG)
actual fun aesGcmDecrypt(
    key: ByteArray, iv: ByteArray, ciphertextWithTag: ByteArray, tagLenBits: Int,
): ByteArray = throw NotImplementedError(MSG)
