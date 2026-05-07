package io.github.tieo.taghistory.apple.findmy

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Vectors cross-generated against Python `findmy/accessory.py`.
 * Paired 2024-06-15T09:30Z in UTC so the first local-4 AM rollover is
 * the next day 04:00Z → secondary offset = 75 slots (matches the Java
 * and Python references).
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class FindMyAccessoryTest {

    private val master = ByteArray(28) { 0xAA.toByte() }
    private val skn = ByteArray(32) { 0xBB.toByte() }
    private val sks = ByteArray(32) { 0xCC.toByte() }
    private val paired: Instant = Instant.parse("2024-06-15T09:30:00Z")

    private fun newAcc(): FindMyAccessory = FindMyAccessory(
        masterKey = master,
        skn = skn,
        sks = sks,
        pairedAt = paired,
        pairedZoneOffsetSeconds = 0,
        name = "TestTag",
        model = "testModel",
        identifier = "test-id",
    )

    @Test
    fun keysAt_pairedInstant_returnsPrimary1AndSecondary1() {
        val got = advB64Set(newAcc().keysAt(paired))
        assertEquals(
            setOf(
                "gfuknMmOHU9x/6yshPCr1DILMpUQZiGyDpd7zg==",
                "ic1v4mdpU7m6MBWtzaoa0yuTB0OhOIYLnjsMeg==",
            ),
            got,
        )
    }

    @Test
    fun keysAt_plus20min_advancesPrimaryOnly() {
        val got = advB64Set(newAcc().keysAt(paired + 20.minutes))
        assertEquals(
            setOf(
                "fIvywVMr2B0pCQS2InMugLODK2Zp59MRyD4gCQ==",
                "ic1v4mdpU7m6MBWtzaoa0yuTB0OhOIYLnjsMeg==",
            ),
            got,
        )
    }

    @Test
    fun keysAt_plus5h_stillBeforeSecondaryRollover() {
        val got = advB64Set(newAcc().keysAt(paired + 5.hours))
        assertEquals(
            setOf(
                "aLezDRhaeS1RfoU26i9R/4Vet+lrHfR9HgxZKQ==",
                "ic1v4mdpU7m6MBWtzaoa0yuTB0OhOIYLnjsMeg==",
            ),
            got,
        )
    }

    @Test
    fun keysAt_pastSecondaryOffset_exposesAdditionalSecondary() {
        // +2d3h: ind=205, offset=75 → primary + secondary[3] (both branches
        // collapse to index 3 here).
        val got = advB64Set(newAcc().keysAt(paired + 2.days + 3.hours))
        assertEquals(
            setOf(
                "rDbn5MI4PSU0WF2FSV1RDJBmKKNJDsWT3Q8NgA==",
                "yHewyaZpySueN16wjXYd5sjKlQno1pE7pc6sCA==",
            ),
            got,
        )
    }

    @Test
    fun keysAt_beforePaired_returnsEmpty() {
        assertTrue(newAcc().keysAt(paired - 1.seconds).isEmpty())
    }

    @Test
    fun keysAt_indexBased_doesNotApplySecondaryOffset() {
        val acc = newAcc()
        val keys = acc.keysAt(1)
        // keysAt(int) uses offset=0, so index > 0 produces two secondary keys.
        assertEquals(3, keys.size)
    }

    @Test
    fun keysBetween_dedupsOnAdv() {
        val acc = newAcc()
        val range = acc.keysBetween(paired, paired + 30.minutes)
        val adv = range.map { Base64.encode(it.advKeyBytes()) }.toSet()
        // Spans two 15-min slots → primary[1], primary[2], secondary[1].
        assertTrue("gfuknMmOHU9x/6yshPCr1DILMpUQZiGyDpd7zg==" in adv)
        assertTrue("fIvywVMr2B0pCQS2InMugLODK2Zp59MRyD4gCQ==" in adv)
        assertTrue("ic1v4mdpU7m6MBWtzaoa0yuTB0OhOIYLnjsMeg==" in adv)
        assertEquals(3, adv.size)
    }

    @Test
    fun interval_isFifteenMinutes() {
        assertEquals(15.minutes, newAcc().interval())
    }

    private fun advB64Set(keys: Set<KeyPair>): Set<String> =
        keys.map { Base64.encode(it.advKeyBytes()) }.toSet()
}
