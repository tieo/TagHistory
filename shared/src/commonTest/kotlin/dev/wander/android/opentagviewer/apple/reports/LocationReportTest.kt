package io.github.tieo.taghistory.apple.reports

import io.github.tieo.taghistory.apple.findmy.KeyPair
import io.github.tieo.taghistory.apple.findmy.hexToBytes
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Vectors cross-generated against Python `findmy/reports/reports.py#LocationReport.decrypt`.
 * The victim private key below is the same one as in [KeyPairTest].
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class LocationReportTest {

    @Test
    fun decrypts89BytePayload_macOs14TrimPath() {
        val payload = Base64.decode(
            "LB3B8H8CBORcKSOHCC7lyBuq6M/JlQ+H5UM15DdlrKxvHmJ2u2mCxKsqWvG02twBkUDYO70" +
                "Kjxz95at1wRNe9IRdaZsYZpEvi6k2vYtsavkHOXlDb6i62uw="
        )
        assertEquals(89, payload.size)

        val report = LocationReport(
            payload,
            Base64.decode(HASHED_ADV_B64),
            Instant.parse("2024-06-15T12:30:00Z"),
            "test",
        )

        assertEquals(2, report.confidence())
        assertEquals(Instant.parse("2024-06-15T12:34:56Z"), report.timestamp())
        assertFalse(report.isDecrypted())

        report.decrypt(KeyPair(VICTIM_PRIV))

        assertTrue(report.isDecrypted())
        assertEquals(37.5, report.latitude(), absoluteTolerance = 1e-7)
        assertEquals(-122.3, report.longitude(), absoluteTolerance = 1e-7)
        assertEquals(25, report.horizontalAccuracy())
        assertEquals(3, report.status())
    }

    @Test
    fun decrypts88BytePayload_legacyLayout() {
        val payload = Base64.decode(
            "K0O0pQEEVGBtGTf0ndOpW36NgCcXUWx79someBmtfig/yozcewWOaDYW0Q+Vq5ZavwmFDiFB" +
                "JWzH3aeffoPC9dYnadNWLaRp2xodMLwXhjOSSUqFwl+KbQ=="
        )
        assertEquals(88, payload.size)

        val report = LocationReport(
            payload,
            Base64.decode(HASHED_ADV_B64),
            Instant.parse("2024-01-02T03:00:00Z"),
            "test",
        )

        assertEquals(1, report.confidence())
        assertEquals(Instant.parse("2024-01-02T03:04:05Z"), report.timestamp())

        report.decrypt(KeyPair(VICTIM_PRIV))
        assertEquals(10.1, report.latitude(), absoluteTolerance = 1e-7)
        assertEquals(-0.05, report.longitude(), absoluteTolerance = 1e-7)
        assertEquals(7, report.horizontalAccuracy())
        assertEquals(5, report.status())
    }

    @Test
    fun rejectsKeyWithDifferentHash() {
        val payload = Base64.decode(
            "K0O0pQEEVGBtGTf0ndOpW36NgCcXUWx79someBmtfig/yozcewWOaDYW0Q+Vq5ZavwmFDiFB" +
                "JWzH3aeffoPC9dYnadNWLaRp2xodMLwXhjOSSUqFwl+KbQ=="
        )
        val report = LocationReport(
            payload,
            ByteArray(32), // all zeros — doesn't match the victim key's hash
            Instant.parse("2024-01-02T03:00:00Z"),
            "",
        )
        assertFailsWith<IllegalArgumentException> { report.decrypt(KeyPair(VICTIM_PRIV)) }
    }

    @Test
    fun latitudeThrowsUntilDecrypted() {
        val payload = Base64.decode(
            "K0O0pQEEVGBtGTf0ndOpW36NgCcXUWx79someBmtfig/yozcewWOaDYW0Q+Vq5ZavwmFDiFB" +
                "JWzH3aeffoPC9dYnadNWLaRp2xodMLwXhjOSSUqFwl+KbQ=="
        )
        val report = LocationReport(
            payload,
            Base64.decode(HASHED_ADV_B64),
            Instant.parse("2024-01-02T03:00:00Z"),
            "",
        )
        assertFailsWith<IllegalStateException> { report.latitude() }
        assertFailsWith<IllegalStateException> { report.longitude() }
        assertFailsWith<IllegalStateException> { report.horizontalAccuracy() }
        assertFailsWith<IllegalStateException> { report.status() }
    }

    @Test
    fun hashedAdvKeyIsReturnedByValue() {
        val hashed = Base64.decode(HASHED_ADV_B64)
        val payload = Base64.decode(
            "K0O0pQEEVGBtGTf0ndOpW36NgCcXUWx79someBmtfig/yozcewWOaDYW0Q+Vq5ZavwmFDiFB" +
                "JWzH3aeffoPC9dYnadNWLaRp2xodMLwXhjOSSUqFwl+KbQ=="
        )
        val report = LocationReport(
            payload, hashed, Instant.parse("2024-01-02T03:00:00Z"), "",
        )
        val got = report.hashedAdvKey()
        assertContentEquals(hashed, got)
        got[0] = 0x42
        assertContentEquals(hashed, report.hashedAdvKey())
    }

    companion object {
        // Victim priv: primary[0] of the (0xAA, 0xBB) master/SKN derivation.
        val VICTIM_PRIV: ByteArray = hexToBytes(
            "01902b2d547e44e4c1d2b10209893cb6f757f705320dbede3adc29f3"
        )
        const val HASHED_ADV_B64 = "QY3iu6/7WVkH71V2aZ8QDSRJb2hs6qWwXaxRRoSl84o="
    }
}
