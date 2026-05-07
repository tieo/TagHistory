package io.github.tieo.taghistory.apple.crypto

/**
 * Sync crypto primitives used by the GSA login flow.
 *
 * We keep these as `expect` functions rather than routing through
 * [dev.whyoleg.cryptography.CryptographyProvider] because:
 *   1. cryptography-kotlin's SHA/HMAC APIs are `suspend`, but SRP-6a has
 *      to call SHA/HMAC a dozen times per step and wrapping every one in
 *      a coroutine is pointless ceremony on JVM where it's sync anyway.
 *   2. The login flow runs on a background coroutine scope already —
 *      parking on sync crypto calls isn't a correctness issue.
 *   3. iOS is not a ship target today, so the missing Apple provider
 *      doesn't block us.
 *
 * Every primitive here is a thin wrapper over the platform's native
 * crypto (`java.security.MessageDigest` / `javax.crypto.Mac` / `Cipher`
 * on Android and desktop). iOS actuals throw — deliberate, not
 * shippable, guarded by the feature gate in AppleLoginService.
 */
expect fun sha256(data: ByteArray): ByteArray

expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/**
 * PBKDF2-HMAC-SHA256 over an arbitrary-byte password. Do not route this
 * through JDK's `PBEKeySpec` — that API forces a `char[]` and lossy
 * widening, which mangles non-ASCII inputs like raw SHA-256 hashes (the
 * `s2k` variant of Apple's password pre-hash depends on this exact
 * byte-level input). The JVM actual implements PBKDF2 directly over
 * `javax.crypto.Mac` so there is no hidden char conversion.
 */
expect fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    dkLenBytes: Int,
): ByteArray

/** AES-128-CBC decrypt with PKCS#7 padding. Throws on auth/pad failure. */
expect fun aesCbcDecryptPkcs7(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray

/**
 * AES-128-GCM decrypt used by the FindMy location-report payload. The
 * ciphertext buffer is the concatenation of the raw ciphertext bytes and
 * the 16-byte authentication tag (JCA-compatible layout). Throws on tag
 * mismatch or padding failure.
 */
expect fun aesGcmDecrypt(
    key: ByteArray,
    iv: ByteArray,
    ciphertextWithTag: ByteArray,
    tagLenBits: Int = 128,
): ByteArray
