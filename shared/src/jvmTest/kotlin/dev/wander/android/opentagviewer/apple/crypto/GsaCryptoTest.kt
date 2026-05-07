package io.github.tieo.taghistory.apple.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Identical vectors to the Java [io.github.tieo.taghistory.apple.crypto.GsaCrypto]
 * tests — cross-generated against the Python `findmy/util/crypto.py`
 * reference shipped in the original app. Any drift here means the
 * Kotlin port would produce different bytes on the wire than the Java
 * port did, and login would break.
 *
 * Lives in `jvmTest` (not `commonTest`) because the expect/actual
 * crypto primitives need a JVM actual to run; Kotlin/Native doesn't
 * have one wired up yet.
 */
class GsaCryptoTest {

    private val salt = hex("cafebabe0000000011223344")

    @Test
    fun encryptPassword_s2k_matchesPython() {
        val out = GsaCrypto.encryptPassword("password123", salt, 2000, GsaCrypto.PROTOCOL_S2K)
        assertEquals(
            hex("a995ae657e16345c93137ffa2b4f5dd98c874a848f30de3aabeffa39f3380c52").toList(),
            out.toList(),
        )
    }

    @Test
    fun encryptPassword_s2kFo_matchesPython() {
        val out = GsaCrypto.encryptPassword("password123", salt, 2000, GsaCrypto.PROTOCOL_S2K_FO)
        assertEquals(
            hex("311224008978615db6aeb23035ce0663c3a15d7c7affa30fb5964c6cb4282f22").toList(),
            out.toList(),
        )
    }

    @Test
    fun decryptSpdAesCbc_matchesPython() {
        val sessionKey = ByteArray(32) { 0x11 }
        val ciphertext = hex(
            "b83c13dc57c79115d56f74dd3d0b2b05" +
                "bdc7738980b083396b4a761b6fa86880" +
                "9267557ce8f51bd07badf4fa40e44105"
        )
        val expected = "hello world, spd plaintext goes here".encodeToByteArray()
        assertEquals(expected.toList(), GsaCrypto.decryptSpdAesCbc(sessionKey, ciphertext).toList())
    }

    @Test
    fun x963Kdf_matchesPython() {
        val secret = hex("deadbeef".repeat(8))
        val info = "update".encodeToByteArray()
        assertEquals(
            hex("69dc13d9b4af030569f1b4bdb59dbc9a").toList(),
            GsaCrypto.x963Kdf(secret, info, 16).toList(),
        )
        assertEquals(
            hex("69dc13d9b4af030569f1b4bdb59dbc9aa88b882026931faa22a8ad3f38ac3b11").toList(),
            GsaCrypto.x963Kdf(secret, info, 32).toList(),
        )
        assertEquals(
            hex(
                "69dc13d9b4af030569f1b4bdb59dbc9a" +
                    "a88b882026931faa22a8ad3f38ac3b11" +
                    "5a0803c325e537bb"
            ).toList(),
            GsaCrypto.x963Kdf(secret, info, 40).toList(),
        )
    }

    @Test
    fun hmacSha256_matchesPython() {
        val out = hmacSha256("key".encodeToByteArray(), "message".encodeToByteArray())
        assertEquals(
            hex(
                "6e9ef29b75fffc5b7abae527d58fdadb" +
                    "2fe42e7219011976917343065f58ed4a"
            ).toList(),
            out.toList(),
        )
    }

    @Test
    fun encryptPassword_rejectsUnknownProtocol() {
        val ex = assertFailsWith<IllegalArgumentException> {
            GsaCrypto.encryptPassword("x", salt, 1, "bogus")
        }
        assertEquals("Unsupported SRP protocol: bogus", ex.message)
    }

    companion object {
        fun hex(s: String): ByteArray {
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
            }
            return out
        }
    }
}
