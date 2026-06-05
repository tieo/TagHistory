package io.github.tieo.taghistory.apple.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-target sanity for the SHA-256 / HMAC-SHA-256 / PBKDF2-HMAC-SHA-256
 * actuals. Runs on every target (jvm, desktop, ios, wasmJs) so the new
 * wasm actuals get tested against the same byte-exact expectations as
 * the BouncyCastle-backed JVM impls.
 */
class CryptoPrimitivesTest {

    private fun ByteArray.hex(): String = joinToString("") {
        val v = it.toInt() and 0xFF
        ((v ushr 4).toString(16) + (v and 0xF).toString(16))
    }

    @Test
    fun `sha256 of "abc" matches NIST vector`() {
        // NIST FIPS 180-4 §A.1 — "abc" → ba7816bf…
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, sha256("abc".encodeToByteArray()).hex())
    }

    @Test
    fun `sha256 empty input matches NIST vector`() {
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, sha256(ByteArray(0)).hex())
    }

    @Test
    fun `hmacSha256 RFC 4231 test case 1`() {
        // RFC 4231 §4.2 — key=0x0b*20, data="Hi There".
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        val expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        assertEquals(expected, hmacSha256(key, data).hex())
    }

    @Test
    fun `pbkdf2HmacSha256 RFC 7914 test vector`() {
        // RFC 7914 §11 — password="passwd", salt="salt", c=1, dkLen=64.
        val expected = "55ac046e56e3089fec1691c22544b605" +
            "f94185216dde0465e68b9d57c20dacbc" +
            "49ca9cccf179b645991664b39d77ef31" +
            "7c71b845b1e30bd509112041d3a19783"
        val actual = pbkdf2HmacSha256(
            password = "passwd".encodeToByteArray(),
            salt = "salt".encodeToByteArray(),
            iterations = 1,
            dkLenBytes = 64,
        )
        assertEquals(expected, actual.hex())
    }
}
