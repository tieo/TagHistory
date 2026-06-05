package io.github.tieo.taghistory.apple.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigIntTest {

    @Test
    fun `byte round-trip preserves value`() {
        val original = bigIntFromString(
            "115792089210356248762697446949407573530086143415290314195533631308867097853951",
        )
        val bytes = original.toMinimalBytes()
        val restored = bigIntFromBytes(bytes)
        assertEquals(0, original.compareTo(restored))
    }

    @Test
    fun `modPow matches the textbook identity`() {
        // 4^13 mod 497 = 445 — classic RSA-style worked example.
        val base = bigIntOf(4L)
        val exp = bigIntOf(13L)
        val mod = bigIntOf(497L)
        assertEquals(bigIntOf(445L), base.modPow(exp, mod))
    }

    @Test
    fun `arithmetic operators behave`() {
        val a = bigIntOf(123L)
        val b = bigIntOf(456L)
        assertEquals(bigIntOf(579L), a + b)
        assertEquals(bigIntOf(-333L), a - b)
        assertEquals(bigIntOf(123L * 456L), a * b)
        assertEquals(bigIntOf(123L % 5L), a.mod(bigIntOf(5L)))
    }

    @Test
    fun `signum matches sign of the underlying value`() {
        assertEquals(1, bigIntOf(5L).signum())
        assertEquals(0, bigIntOf(0L).signum())
        assertEquals(-1, (bigIntOf(0L) - bigIntOf(5L)).signum())
    }

    @Test
    fun `toMinimalBytes drops a leading sign zero`() {
        // Top bit of 0x80… is set; java.math.BigInteger.toByteArray
        // would prepend 0x00 — toMinimalBytes must strip that.
        val n = bigIntFromString("80", radix = 16)
        val bytes = n.toMinimalBytes()
        assertEquals(1, bytes.size)
        assertTrue(bytes[0].toInt() and 0xFF == 0x80)
    }
}
