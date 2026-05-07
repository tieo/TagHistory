package io.github.tieo.taghistory.apple.reports

import io.github.tieo.taghistory.apple.crypto.aesGcmDecrypt
import io.github.tieo.taghistory.apple.crypto.sha256
import io.github.tieo.taghistory.apple.findmy.KeyPair
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * KMP port of `findmy/reports/reports.py#LocationReport`. Wraps one raw
 * payload blob returned from Apple's `acsnservice/fetch` and lets the
 * caller decrypt it with the matching [KeyPair].
 *
 * Wire format (Python reference comments inline):
 *  - `payload[0..4)` — BE uint32 seconds since 2011-01-01T00:00:00Z
 *  - `payload[4]`    — confidence when total length is 88
 *  - `payload[5]`    — confidence otherwise
 *  - `payload[4..]`  — encrypted blob; macOS 14+ prepends a single byte
 *    that we trim (detected by remaining length == 85).
 *
 * Decrypted layout:
 *  - `[0]`     leading byte (ignored)
 *  - `[1..58)` uncompressed P-224 public point `0x04 || X || Y`
 *  - `[58..68)` 10 bytes AES-GCM ciphertext
 *  - `[68..]`  16 bytes AES-GCM tag
 *
 * Shared secret = `X(d · eph_pub)`. Key material = `SHA256(X || be32(1) || eph_pub)`,
 * split as AES-128 key (first 16 bytes) + GCM IV (last 16 bytes).
 * Plaintext is 10 bytes: `[0..4) latE7`, `[4..8) lonE7`, `[8] accuracy`,
 * `[9] status`.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class LocationReport(
    payload: ByteArray,
    hashedAdvKey: ByteArray,
    val publishedAt: Instant,
    description: String?,
) {
    init {
        require(payload.size >= 5) { "payload too short: ${payload.size}" }
    }

    private val payloadBytes: ByteArray = payload.copyOf()
    private val hashedAdvKeyBytes: ByteArray = hashedAdvKey.copyOf()
    val description: String = description ?: ""

    private var plaintext: ByteArray? = null
    private var decryptedWith: KeyPair? = null

    fun payload(): ByteArray = payloadBytes.copyOf()
    fun hashedAdvKey(): ByteArray = hashedAdvKeyBytes.copyOf()
    fun hashedAdvKeyB64(): String = Base64.encode(hashedAdvKeyBytes)
    fun isDecrypted(): Boolean = plaintext != null
    fun key(): KeyPair = decryptedWith ?: error("not decrypted")

    /**
     * Recorded-at timestamp (not published-at). The 4-byte prefix is
     * seconds since 2011-01-01; rebase to the Unix epoch.
     */
    fun timestamp(): Instant {
        val offset = ((payloadBytes[0].toLong() and 0xFF) shl 24) or
            ((payloadBytes[1].toLong() and 0xFF) shl 16) or
            ((payloadBytes[2].toLong() and 0xFF) shl 8) or
            (payloadBytes[3].toLong() and 0xFF)
        return Instant.fromEpochSeconds(offset + APPLE_EPOCH_OFFSET_SECONDS)
    }

    /** 1..3 confidence value. Layout depends on total payload size. */
    fun confidence(): Int {
        val b = if (payloadBytes.size == 88) payloadBytes[4] else payloadBytes[5]
        return b.toInt() and 0xFF
    }

    fun latitude(): Double {
        val pt = requireDecrypted()
        return readIntBE(pt, 0) / 1.0e7
    }

    fun longitude(): Double {
        val pt = requireDecrypted()
        return readIntBE(pt, 4) / 1.0e7
    }

    fun horizontalAccuracy(): Int {
        val pt = requireDecrypted()
        return pt[8].toInt() and 0xFF
    }

    fun status(): Int {
        val pt = requireDecrypted()
        return pt[9].toInt() and 0xFF
    }

    fun decrypt(key: KeyPair) {
        if (!key.hashedAdvKeyBytes().contentEquals(hashedAdvKeyBytes)) {
            throw IllegalArgumentException("key hashedAdvKey mismatch")
        }
        if (isDecrypted()) return

        var encrypted = payloadBytes.copyOfRange(4, payloadBytes.size)
        // macOS 14+ prepends a single byte — trim when the section is 85 bytes.
        if (encrypted.size == 85) {
            encrypted = encrypted.copyOfRange(1, encrypted.size)
        }
        require(encrypted.size >= 1 + 57 + 10 + 16) {
            "encrypted section too short: ${encrypted.size}"
        }

        val ephPub = encrypted.copyOfRange(1, 58)         // 57 bytes (0x04 || X || Y)
        val ct = encrypted.copyOfRange(58, 68)            // 10 bytes
        val tag = encrypted.copyOfRange(68, encrypted.size) // 16 bytes

        val shared = key.dhExchange(ephPub)
        val symInput = ByteArray(shared.size + 4 + ephPub.size).also {
            shared.copyInto(it, destinationOffset = 0)
            // symInput[shared.size..shared.size+3] is be32(1) — bytes 0,0,0,1.
            it[shared.size + 3] = 0x01
            ephPub.copyInto(it, destinationOffset = shared.size + 4)
        }
        val symmetric = sha256(symInput)

        val aesKey = symmetric.copyOfRange(0, 16)
        val iv = symmetric.copyOfRange(16, 32)

        val gcmInput = ByteArray(ct.size + tag.size).also {
            ct.copyInto(it, destinationOffset = 0)
            tag.copyInto(it, destinationOffset = ct.size)
        }

        plaintext = aesGcmDecrypt(aesKey, iv, gcmInput)
        decryptedWith = key
    }

    private fun requireDecrypted(): ByteArray =
        plaintext ?: error("report is not decrypted")

    private fun readIntBE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    companion object {
        /** Apple's reference epoch for the 4-byte timestamp prefix (2011-01-01T00Z). */
        const val APPLE_EPOCH_OFFSET_SECONDS: Long = 60L * 60 * 24 * 11323
    }
}
