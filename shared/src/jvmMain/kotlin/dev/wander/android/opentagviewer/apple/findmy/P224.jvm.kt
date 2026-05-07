package io.github.tieo.taghistory.apple.findmy

import java.math.BigInteger
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters

actual object P224 {
    private val CURVE: X9ECParameters = ECNamedCurveTable.getByName("secp224r1")
    private const val SCALAR_BYTES = 28

    actual fun derivePublicX(privateKey: ByteArray): ByteArray {
        val d = BigInteger(1, privateKey)
        val q = CURVE.g.multiply(d).normalize()
        return toFixedLength(q.affineXCoord.toBigInteger(), SCALAR_BYTES)
    }

    actual fun dhExchangeX(privateKey: ByteArray, otherPubEncoded: ByteArray): ByteArray {
        val d = BigInteger(1, privateKey)
        val pub = CURVE.curve.decodePoint(otherPubEncoded)
        val shared = pub.multiply(d).normalize()
        return toFixedLength(shared.affineXCoord.toBigInteger(), SCALAR_BYTES)
    }

    // BigInteger.toByteArray inserts a leading zero when the high bit is
    // set, and strips leading zeros otherwise. Neither matches the
    // fixed-length, unsigned, big-endian layout FindMy needs on the wire.
    private fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            raw.size == length + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < length -> ByteArray(length).also {
                raw.copyInto(it, destinationOffset = length - raw.size)
            }
            else -> throw IllegalArgumentException(
                "value does not fit in $length bytes (got ${raw.size})"
            )
        }
    }
}
