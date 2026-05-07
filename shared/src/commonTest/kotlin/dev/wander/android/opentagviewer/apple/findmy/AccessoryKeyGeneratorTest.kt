package io.github.tieo.taghistory.apple.findmy

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Vectors cross-generated against Python `findmy/util/crypto.py`. Any
 * drift means on-device key derivation will disagree with iCloud's index.
 */
@OptIn(ExperimentalEncodingApi::class)
class AccessoryKeyGeneratorTest {

    @Test
    fun secretKeyAt_matchesPython_primary() {
        val g = AccessoryKeyGenerator(MASTER, SKN, KeyType.PRIMARY)
        assertContentEquals(
            hexToBytes("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
            g.secretKeyAt(0),
        )
        assertContentEquals(
            hexToBytes("fc969ebead7daabade1e446dd44656c1036f7acfcbd03aa91f018fd05d5d5a22"),
            g.secretKeyAt(1),
        )
        assertContentEquals(
            hexToBytes("2d8a0dad7a559bc29dba0e936cbb5720060e85243dbb657053a1cbd8ffdf59cf"),
            g.secretKeyAt(2),
        )
        assertContentEquals(
            hexToBytes("f9843cb30911a5aa79b34bfc9838f5902e825d64bee3c3e39b975f871bdf53fc"),
            g.secretKeyAt(3),
        )
        assertContentEquals(
            hexToBytes("9c736b07fb05cf5c647c0b9b3c9fcbb906f8f405a7d571fce9fa48d872d05a61"),
            g.secretKeyAt(10),
        )
    }

    @Test
    fun secretKeyAt_rewinds() {
        val g = AccessoryKeyGenerator(MASTER, SKN, KeyType.PRIMARY)
        g.secretKeyAt(10)
        // Rewind — must restart from initial SKN.
        assertContentEquals(
            hexToBytes("fc969ebead7daabade1e446dd44656c1036f7acfcbd03aa91f018fd05d5d5a22"),
            g.secretKeyAt(1),
        )
    }

    @Test
    fun secretKeyAt_matchesPython_secondary() {
        val g = AccessoryKeyGenerator(MASTER, SKS, KeyType.SECONDARY)
        assertContentEquals(
            hexToBytes("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            g.secretKeyAt(0),
        )
        assertContentEquals(
            hexToBytes("0c9be7459983a6f81fec9df3eb8569b8bc021d0c2ba2c9729a5140bba0915b2c"),
            g.secretKeyAt(1),
        )
        assertContentEquals(
            hexToBytes("1229e603d68c91f9160de146f2f2a341392c94a4d0028923dd7102f986e83b64"),
            g.secretKeyAt(2),
        )
    }

    @Test
    fun derivePsKey_matchesPython() {
        val sk0 = repeat(0xBB.toByte(), 32)
        val priv = AccessoryKeyGenerator.derivePsKey(MASTER, sk0)
        assertContentEquals(
            hexToBytes("01902b2d547e44e4c1d2b10209893cb6f757f705320dbede3adc29f3"),
            priv,
        )
    }

    @Test
    fun keyAt_producesAdvMatchingPython_primary() {
        val g = AccessoryKeyGenerator(MASTER, SKN, KeyType.PRIMARY)
        assertEquals("Y/ZM3M0JOQVxQhaXt5Z6Zv/amzm3ckbWHe9xhg==", Base64.encode(g.keyAt(0).advKeyBytes()))
        assertEquals("gfuknMmOHU9x/6yshPCr1DILMpUQZiGyDpd7zg==", Base64.encode(g.keyAt(1).advKeyBytes()))
        assertEquals("fIvywVMr2B0pCQS2InMugLODK2Zp59MRyD4gCQ==", Base64.encode(g.keyAt(2).advKeyBytes()))
        assertEquals("hcrLlrLtWfvAu0lQ1OLIxTJ0o6Ykf1HMtMkHqg==", Base64.encode(g.keyAt(10).advKeyBytes()))
        assertEquals("O1R1lIKLncuunJAx0bCKEjWVFxCmoLIjk+wwhQ==", Base64.encode(g.keyAt(100).advKeyBytes()))
    }

    @Test
    fun keyAt_producesAdvMatchingPython_secondary() {
        val g = AccessoryKeyGenerator(MASTER, SKS, KeyType.SECONDARY)
        assertEquals("nsDyINv+TM5vUYFpn8nBmCYX+VBu7N4/g4yQww==", Base64.encode(g.keyAt(0).advKeyBytes()))
        assertEquals("ic1v4mdpU7m6MBWtzaoa0yuTB0OhOIYLnjsMeg==", Base64.encode(g.keyAt(1).advKeyBytes()))
        assertEquals("ICy5etfBbdsqrF1STPoDs0LCQGGBjF7JqpWBTg==", Base64.encode(g.keyAt(2).advKeyBytes()))
    }

    @Test
    fun keyAt_marksKeyType() {
        val p = AccessoryKeyGenerator(MASTER, SKN, KeyType.PRIMARY)
        val s = AccessoryKeyGenerator(MASTER, SKS, KeyType.SECONDARY)
        assertEquals(KeyType.PRIMARY, p.keyAt(0).keyType)
        assertEquals(KeyType.SECONDARY, s.keyAt(0).keyType)
    }

    companion object {
        val MASTER: ByteArray = repeat(0xAA.toByte(), 28)
        val SKN: ByteArray = repeat(0xBB.toByte(), 32)
        val SKS: ByteArray = repeat(0xCC.toByte(), 32)

        fun repeat(v: Byte, n: Int): ByteArray = ByteArray(n) { v }
    }
}
