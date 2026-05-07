package io.github.tieo.taghistory.apple.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported verbatim from the Java [Srp6aGsaTest]. Vectors were recorded
 * from a deterministic pysrp `Verifier` run with pinned ephemerals, and
 * verified round-trip before the Java port shipped. The Kotlin port
 * MUST produce bit-identical M1/K/M2, since the server on the other end
 * is pysrp-compatible.
 */
class Srp6aGsaTest {

    private val fixedARaw: ByteArray = run {
        // Our Srp6aGsa ORs 0x80 into buf[0] after the RNG fills it. Feed
        // it [0x00, 0x11, 0x11, ...] so the top-bit-set reproduces the
        // Python vector's buf[0]=0x80.
        val a = ByteArray(256)
        a[0] = 0x00
        for (i in 1 until a.size) a[i] = 0x11
        a
    }

    private val salt = hex("0102030405060708090a0b0c0d0e0f10")
    private val pbkdfOut = ByteArray(32) { 0xAB.toByte() }

    private val expectedA = hex(
        "73ecb088eb394429785335dcfdeba9fe3927a62752d2221019608321fc3e3816" +
            "a4f25c2ca5a3ef8702df7c07193f948dc700875b8ca0a34437b3e997b071a46f" +
            "2885e4d08e0e7db727b3baf5aa016e6020cf2bedbc603cab82b7d36f1420b5c1" +
            "d32782b068e474dd2a791b701e5f82c253e4b479a744b9a3f47ae46f09ecd59b" +
            "7819bcc4f06e037763bd85aba1c11d1c3c83983176b075e782cfc8da7f3084e1" +
            "f167e18461337bc8bad2652daff2a881c4dd8e3fc0fdb48df4fcf71bb16905ea" +
            "972f25cf071df4239dc4d8c9513301c5fefbe554cd6a6dd3692c0b2a8a9e2ac7" +
            "f54cd5b4c76771eeb231a64a33f10850093190e1dced46d7554493fdfe2bdefe"
    )

    private val serverB = hex(
        "13c9a9bc6d120c9e9c86e5760f04d12f640e366628d0bcf14ad5b8cd096f5711" +
            "5dbefb9b4d064e1293312da4ea8efc1e0f5783a4c00e79b12892fa48855ef4fa" +
            "1953f272f53a2f47880d39f8fe72b8bbb1a1a53d97511032fab4b99d699cc2e3" +
            "e9ca5945fc30660c52408323a5a9947d457f9343e8520433593348765d1971cd" +
            "474778432397e9100cc65ec30fc7734a11a9600e80d4398f8499f660201c33c0" +
            "8f54ba4f723ac90b487add9a96c1451c80fbad977effc7b0fc61b71f4a1efc40" +
            "c9df35aca5e5a2d26fbb89eb998f5d8a2add4c7ed952846c573030d0816e7f0b" +
            "1e283d82f8337224cc3033caae22b9b2afd40eb4f16e58782348816153461dec"
    )

    private val expectedM1 = hex("dfd53fa87c4c218049ae59adb7ee95873a877cf1fabaac26d0b2fa30f1202546")
    private val expectedK = hex("1d1e060772c50cd42fcf92d8fff49c87173b5bf226767b6f532eecbe0c30af8b")
    private val serverM2 = hex("ea823e41e87d0aec3a9aff445564f97d4f48a3e61eba82660480666155bc5d09")

    @Test
    fun handshake_matchesPysrpVector() {
        val srp = Srp6aGsa("user@example.com", fixedRng(fixedARaw))

        assertEquals(expectedA.toList(), srp.startAuthentication().toList())

        val m1 = srp.processChallenge(salt, serverB, pbkdfOut)
        assertEquals(expectedM1.toList(), m1.toList())
        assertEquals(expectedK.toList(), srp.getSessionKey()!!.toList())

        assertTrue(srp.verifySession(serverM2))
    }

    @Test
    fun verifySession_rejectsMismatch() {
        val srp = Srp6aGsa("user@example.com", fixedRng(fixedARaw))
        srp.startAuthentication()
        srp.processChallenge(salt, serverB, pbkdfOut)
        val wrong = serverM2.copyOf()
        wrong[0] = (wrong[0].toInt() xor 0x01).toByte()
        assertFalse(srp.verifySession(wrong))
    }

    @Test
    fun processChallenge_rejectsBCongruentZero() {
        val srp = Srp6aGsa("user@example.com", fixedRng(fixedARaw))
        srp.startAuthentication()
        assertFailsWith<IllegalStateException> {
            srp.processChallenge(salt, byteArrayOf(0), pbkdfOut)
        }
    }

    @Test
    fun processChallenge_requiresStartFirst() {
        assertFailsWith<IllegalStateException> {
            Srp6aGsa("x", fixedRng(fixedARaw)).processChallenge(salt, serverB, pbkdfOut)
        }
    }

    @Test
    fun verifySession_requiresChallengeFirst() {
        assertFailsWith<IllegalStateException> {
            Srp6aGsa("x", fixedRng(fixedARaw)).verifySession(serverM2)
        }
    }

    private fun fixedRng(payload: ByteArray): Rng = Rng { out ->
        payload.copyInto(out, endIndex = minOf(payload.size, out.size))
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
