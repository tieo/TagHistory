package io.github.tieo.taghistory.apple.findmy

import kotlin.test.Test
import kotlin.test.assertEquals

class P224Test {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            ((clean[2 * it].digitToInt(16) shl 4) or clean[2 * it + 1].digitToInt(16)).toByte()
        }
    }

    private fun ByteArray.hex(): String =
        joinToString("") { ((it.toInt() and 0xFF).toString(16).padStart(2, '0')) }

    @Test
    fun `derivePublicX matches BouncyCastle for a known scalar`() {
        // Reference computed with python `cryptography` (NIST P-224):
        //   priv = 1
        //   X(priv * G) = Gx = b70e0cbd6bb4bf7f321390b94a03c1d356c21122343280d6115c1d21
        val priv = hex("00000000000000000000000000000000000000000000000000000001")
        val expected = "b70e0cbd6bb4bf7f321390b94a03c1d356c21122343280d6115c1d21"
        assertEquals(expected, P224.derivePublicX(priv).hex())
    }

    @Test
    fun `derivePublicX with scalar 2 matches reference`() {
        // X(2 * G) = 706a46dc76dcb76798e60e6d89474788d16dc18032d268fd1a704fa6
        val priv = hex("00000000000000000000000000000000000000000000000000000002")
        val expected = "706a46dc76dcb76798e60e6d89474788d16dc18032d268fd1a704fa6"
        assertEquals(expected, P224.derivePublicX(priv).hex())
    }

    @Test
    fun `derivePublicX with scalar 13 matches reference`() {
        // X(13 * G) from python cryptography.
        val priv = hex("0000000000000000000000000000000000000000000000000000000d")
        val expected = "34e8e17a430e43289793c383fac9774247b40e9ebd3366981fcfaeca"
        assertEquals(expected, P224.derivePublicX(priv).hex())
    }

    @Test
    fun `dhExchangeX symmetry — Alice*Bob == Bob*Alice`() {
        // priv_a = 7, priv_b = 13. Both public points pre-computed via
        // python cryptography (NIST SECP224R1) so the JVM BouncyCastle
        // point validator accepts the uncompressed encoding.
        val a = hex("00000000000000000000000000000000000000000000000000000007")
        val b = hex("0000000000000000000000000000000000000000000000000000000d")
        val pubA = hex(
            "04" +
                "db2f6be630e246a5cf7d99b85194b123d487e2d466b94b24a03c3e28" +
                "0f3a30085497f2f611ee2517b163ef8c53b715d18bb4e4808d02b963"
        )
        val pubB = hex(
            "04" +
                "34e8e17a430e43289793c383fac9774247b40e9ebd3366981fcfaeca" +
                "252819f71c7fb7fbcb159be337d37d3336d7feb963724fdfb0ecb767"
        )
        val secretA = P224.dhExchangeX(a, pubB).hex()
        val secretB = P224.dhExchangeX(b, pubA).hex()
        assertEquals(secretA, secretB)
    }

    @Test
    fun `dhExchangeX accepts compressed point encoding`() {
        // Bob's pub from the symmetry test, but compressed (0x02/0x03 || X).
        // Compressed sign byte is 0x03 when Y is odd, 0x02 when even.
        // For 13G the canonical Y above ends in 67 (odd) → 0x03.
        val a = hex("00000000000000000000000000000000000000000000000000000007")
        val pubBCompressed = hex(
            "03" +
                "34e8e17a430e43289793c383fac9774247b40e9ebd3366981fcfaeca"
        )
        val pubBUncompressed = hex(
            "04" +
                "34e8e17a430e43289793c383fac9774247b40e9ebd3366981fcfaeca" +
                "252819f71c7fb7fbcb159be337d37d3336d7feb963724fdfb0ecb767"
        )
        assertEquals(
            P224.dhExchangeX(a, pubBUncompressed).hex(),
            P224.dhExchangeX(a, pubBCompressed).hex(),
        )
    }
}
