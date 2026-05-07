package io.github.tieo.taghistory.apple.findmy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Vectors cross-generated against Python `findmy/keys.py`. Any drift here
 * means on-device P-224 derivation disagrees with iCloud's index.
 */
class KeyPairTest {

    @Test
    fun derivesPublicXMatchingPython() {
        val priv = hex("01902b2d547e44e4c1d2b10209893cb6f757f705320dbede3adc29f3")
        val kp = KeyPair(priv)
        assertEquals("Y/ZM3M0JOQVxQhaXt5Z6Zv/amzm3ckbWHe9xhg==", kp.advKeyB64())
        assertEquals("QY3iu6/7WVkH71V2aZ8QDSRJb2hs6qWwXaxRRoSl84o=", kp.hashedAdvKeyB64())
    }

    @Test
    fun privateKeyRoundTripsThroughBase64() {
        val priv = hex("056a6d1494db7745844e231a69e4692e102c1af774bb11bec67e7b4d")
        val kp = KeyPair(priv)
        val decoded = KeyPair.fromB64(kp.privateKeyB64())
        assertContentEquals(priv, decoded.privateKeyBytes())
        assertContentEquals(kp.advKeyBytes(), decoded.advKeyBytes())
    }

    @Test
    fun rejectsWrongPrivateKeyLength() {
        assertFailsWith<IllegalArgumentException> { KeyPair(ByteArray(27)) }
        assertFailsWith<IllegalArgumentException> { KeyPair(ByteArray(29)) }
    }

    @Test
    fun equalityIsBasedOnAdvKey() {
        val priv = hex("01902b2d547e44e4c1d2b10209893cb6f757f705320dbede3adc29f3")
        val a = KeyPair(priv, KeyType.PRIMARY, "a")
        val b = KeyPair(priv, KeyType.SECONDARY, "b")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val c = KeyPair(hex("056a6d1494db7745844e231a69e4692e102c1af774bb11bec67e7b4d"))
        assertNotEquals(a, c)
    }

    @Test
    fun advKeyIsTwentyEightBytes() {
        val priv = hex("01902b2d547e44e4c1d2b10209893cb6f757f705320dbede3adc29f3")
        val kp = KeyPair(priv)
        assertEquals(28, kp.advKeyBytes().size)
        assertEquals(32, kp.hashedAdvKeyBytes().size)
    }

    @Test
    fun privateKeyBytesReturnsCopy() {
        val priv = hex("01902b2d547e44e4c1d2b10209893cb6f757f705320dbede3adc29f3")
        val kp = KeyPair(priv)
        val a = kp.privateKeyBytes()
        val b = kp.privateKeyBytes()
        a[0] = 0x42
        assertTrue(a[0] != b[0])
    }

    companion object {
        fun hex(h: String): ByteArray = hexToBytes(h)
    }
}

internal fun hexToBytes(h: String): ByteArray {
    val out = ByteArray(h.length / 2)
    for (i in out.indices) {
        val hi = hexNibble(h[i * 2])
        val lo = hexNibble(h[i * 2 + 1])
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun hexNibble(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> 10 + (c - 'a')
    in 'A'..'F' -> 10 + (c - 'A')
    else -> throw IllegalArgumentException("Not a hex digit: $c")
}
