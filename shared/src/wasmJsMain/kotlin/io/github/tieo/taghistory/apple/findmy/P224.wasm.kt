package io.github.tieo.taghistory.apple.findmy

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign

/**
 * NIST P-224 (secp224r1) actual for wasmJs. Pure-Kotlin EC math via
 * ionspin BigInteger. JVM/Android use BouncyCastle; this path is
 * algorithmically equivalent so the X-coordinates of derived public
 * keys + ECDH shared secrets match byte-for-byte with the JVM actual.
 *
 * Affine arithmetic, double-and-add scalar mul. Slow vs Bouncy's
 * Jacobian curve, but FindMy invokes us per beacon (a few hundred ms
 * for the worst-case derivation), not per packet.
 */
@OptIn(ExperimentalUnsignedTypes::class)
actual object P224 {

    private val P = BigInteger.parseString(
        "26959946667150639794667015087019630673557916260026308143510066298881",
    )
    private val B = BigInteger.parseString(
        "b4050a850c04b3abf54132565044b0b7d7bfd8ba270b39432355ffb4",
        16,
    )
    private val GX = BigInteger.parseString(
        "b70e0cbd6bb4bf7f321390b94a03c1d356c21122343280d6115c1d21",
        16,
    )
    private val GY = BigInteger.parseString(
        "bd376388b5f723fb4c22dfe6cd4375a05a07476444d5819985007e34",
        16,
    )

    actual fun derivePublicX(privateKey: ByteArray): ByteArray {
        val k = BigInteger.fromByteArray(privateKey, Sign.POSITIVE)
        val (x, _) = scalarMul(k, GX, GY)
        return to28Bytes(x)
    }

    actual fun dhExchangeX(privateKey: ByteArray, otherPubEncoded: ByteArray): ByteArray {
        val k = BigInteger.fromByteArray(privateKey, Sign.POSITIVE)
        val (px, py) = decodePoint(otherPubEncoded)
        val (x, _) = scalarMul(k, px, py)
        return to28Bytes(x)
    }

    // --- Curve arithmetic ---

    private val THREE = BigInteger.fromLong(3)
    private val TWO = BigInteger.fromLong(2)

    // ionspin BigInteger.modInverse is only well-defined for positive
    // operands, so normalize before inverting.
    private fun modInverse(a: BigInteger, m: BigInteger): BigInteger = a.mod(m).modInverse(m)

    private fun pointDouble(x: BigInteger, y: BigInteger): Pair<BigInteger, BigInteger> {
        if (y.isZero()) return BigInteger.ZERO to BigInteger.ZERO
        val s = (((THREE * x * x - THREE).mod(P)) * modInverse(TWO * y, P)).mod(P)
        val xr = (s * s - TWO * x).mod(P)
        val yr = (s * (x - xr) - y).mod(P)
        return xr to yr
    }

    private fun pointAdd(
        x1: BigInteger, y1: BigInteger,
        x2: BigInteger, y2: BigInteger,
    ): Pair<BigInteger, BigInteger> {
        // Point at infinity handling.
        if (x1.isZero() && y1.isZero()) return x2 to y2
        if (x2.isZero() && y2.isZero()) return x1 to y1
        if (x1 == x2) {
            return if (y1 == y2) pointDouble(x1, y1)
            else BigInteger.ZERO to BigInteger.ZERO // y1 == -y2 → infinity
        }
        val s = (((y2 - y1).mod(P)) * modInverse(x2 - x1, P)).mod(P)
        val xr = (s * s - x1 - x2).mod(P)
        val yr = (s * (x1 - xr) - y1).mod(P)
        return xr to yr
    }

    private fun scalarMul(
        k: BigInteger,
        bx: BigInteger,
        by: BigInteger,
    ): Pair<BigInteger, BigInteger> {
        // Classic double-and-add. Constant-time is out of scope —
        // FindMy doesn't expose a side-channel surface in browser
        // context (no remote timing oracle).
        var rx = BigInteger.ZERO
        var ry = BigInteger.ZERO
        var ax = bx
        var ay = by
        var e = k
        while (e > BigInteger.ZERO) {
            if (e and BigInteger.ONE == BigInteger.ONE) {
                val sum = pointAdd(rx, ry, ax, ay)
                rx = sum.first; ry = sum.second
            }
            val doubled = pointDouble(ax, ay)
            ax = doubled.first; ay = doubled.second
            e = e shr 1
        }
        return rx to ry
    }

    // --- Encoding helpers ---

    private fun to28Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(28)
        val src = if (raw.size > 28) raw.copyOfRange(raw.size - 28, raw.size) else raw
        src.copyInto(out, 28 - src.size, 0, src.size)
        return out
    }

    /**
     * Decode either an uncompressed (0x04 || X || Y, 57 bytes) or
     * compressed (0x02/0x03 || X, 29 bytes) point. Compressed form
     * needs a modular square root mod p which is one Tonelli-Shanks
     * shortcut for primes ≡ 3 mod 4 — but P-224's prime is ≡ 1 mod 4,
     * so the full Tonelli-Shanks loop is implemented.
     */
    private fun decodePoint(encoded: ByteArray): Pair<BigInteger, BigInteger> {
        return when {
            encoded.size == 57 && encoded[0].toInt() == 0x04 -> {
                val x = BigInteger.fromByteArray(encoded.copyOfRange(1, 29), Sign.POSITIVE)
                val y = BigInteger.fromByteArray(encoded.copyOfRange(29, 57), Sign.POSITIVE)
                x to y
            }
            encoded.size == 29 && (encoded[0].toInt() == 0x02 || encoded[0].toInt() == 0x03) -> {
                val x = BigInteger.fromByteArray(encoded.copyOfRange(1, 29), Sign.POSITIVE)
                val ySquared = (x.pow(3) - THREE * x + B).mod(P)
                var y = tonelliShanks(ySquared, P)
                val want = encoded[0].toInt() and 1
                if ((y and BigInteger.ONE).intValue(false) != want) y = (P - y).mod(P)
                x to y
            }
            else -> throw IllegalArgumentException(
                "P-224 point encoding must be 29 (compressed) or 57 (uncompressed) bytes, got ${encoded.size}"
            )
        }
    }

    /**
     * Modular square root via Tonelli-Shanks. P-224's prime p satisfies
     * `p = 1 mod 4`, so the simpler `n^((p+1)/4) mod p` shortcut does
     * not apply. We pre-compute Q + S for the curve's prime and only
     * use this once per ECDH call, so cost is in the noise.
     */
    private fun tonelliShanks(n: BigInteger, p: BigInteger): BigInteger {
        // p - 1 = Q * 2^S with Q odd
        var q = p - BigInteger.ONE
        var s = 0
        while (q and BigInteger.ONE == BigInteger.ZERO) {
            q = q shr 1
            s++
        }
        // Find a quadratic non-residue z
        var z = TWO
        while (modPow(z, (p - BigInteger.ONE) shr 1, p) != p - BigInteger.ONE) z = z + BigInteger.ONE

        var m = s
        var c = modPow(z, q, p)
        var t = modPow(n, q, p)
        var r = modPow(n, (q + BigInteger.ONE) shr 1, p)

        while (t != BigInteger.ONE) {
            var i = 0
            var temp = t
            while (temp != BigInteger.ONE) {
                temp = (temp * temp).mod(p)
                i++
                if (i == m) throw IllegalStateException("Tonelli-Shanks: not a quadratic residue")
            }
            val b = modPow(c, BigInteger.ONE shl (m - i - 1), p)
            m = i
            c = (b * b).mod(p)
            t = (t * c).mod(p)
            r = (r * b).mod(p)
        }
        return r
    }

    private fun modPow(base: BigInteger, exp: BigInteger, m: BigInteger): BigInteger {
        var result = BigInteger.ONE
        var b = base.mod(m)
        var e = exp
        while (e > BigInteger.ZERO) {
            if (e and BigInteger.ONE == BigInteger.ONE) result = (result * b).mod(m)
            e = e shr 1
            if (e > BigInteger.ZERO) b = (b * b).mod(m)
        }
        return result
    }
}
