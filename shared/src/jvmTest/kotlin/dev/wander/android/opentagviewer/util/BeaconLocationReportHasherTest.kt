package io.github.tieo.taghistory.util

import io.github.tieo.taghistory.data.model.BeaconLocationReport
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the Kotlin hasher byte-identical with the Java one the on-disk
 * rows were written against. The oracle is Java's own `String.format` +
 * `MessageDigest` — if drift appears, old rows become unreachable by
 * hash lookup.
 */
class BeaconLocationReportHasherTest {

    private fun javaHash(beaconId: String, r: BeaconLocationReport): String {
        val encoding = String.format(
            Locale.ROOT,
            "%s-%d-%d-%d-%s-%.15f-%.15f",
            beaconId,
            r.status,
            r.timestamp,
            r.publishedAt,
            r.description,
            r.latitude,
            r.longitude,
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encoding.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            val hex = Integer.toHexString(v)
            if (hex.length == 1) "0$hex" else hex
        }
    }

    private fun sample(lat: Double, lon: Double): BeaconLocationReport = BeaconLocationReport(
        publishedAt = 1_700_000_000_000L,
        description = "nearby",
        timestamp = 1_699_999_500_000L,
        confidence = 42L,
        latitude = lat,
        longitude = lon,
        horizontalAccuracy = 10L,
        status = 7L,
    )

    @Test
    fun `matches Java oracle on typical European coords`() {
        val r = sample(lat = 52.51952000000001, lon = 13.40670000000001)
        assertEquals(javaHash("beacon-A", r), BeaconLocationReportHasher.getSha256HashFor("beacon-A", r))
    }

    @Test
    fun `matches Java oracle on negative + southern hemisphere coords`() {
        val r = sample(lat = -33.865143, lon = -151.209900)
        assertEquals(javaHash("b", r), BeaconLocationReportHasher.getSha256HashFor("b", r))
    }

    @Test
    fun `matches Java oracle at origin`() {
        val r = sample(lat = 0.0, lon = 0.0)
        assertEquals(javaHash("origin", r), BeaconLocationReportHasher.getSha256HashFor("origin", r))
    }

    @Test
    fun `formatDecimal15 matches Java format on a battery of values`() {
        // Finite, non-tie values. Java uses HALF_UP on ties, which is
        // delicate across float reps; we cover the typical fixed
        // GPS-style inputs the Java app produces.
        val samples = listOf(
            0.0,
            -0.0,
            1.0,
            -1.0,
            3.141592653589793,
            -3.141592653589793,
            52.51952,
            -33.865143,
            180.0,
            -180.0,
            0.000000000000001,
            1234.5678,
        )
        for (v in samples) {
            val expected = String.format(Locale.ROOT, "%.15f", v)
            val actual = BeaconLocationReportHasher.formatDecimal15(v)
            assertEquals(expected, actual, "mismatch on $v")
        }
    }

    @Test
    fun `formatDecimal15 handles non-finite`() {
        assertEquals("NaN", BeaconLocationReportHasher.formatDecimal15(Double.NaN))
        assertEquals("Infinity", BeaconLocationReportHasher.formatDecimal15(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", BeaconLocationReportHasher.formatDecimal15(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `hash bytes are 32 bytes and hex is 64 chars`() {
        val r = sample(52.0, 13.0)
        assertEquals(32, BeaconLocationReportHasher.getSha256BytesFor("b", r).size)
        val hex = BeaconLocationReportHasher.getSha256HashFor("b", r)
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
