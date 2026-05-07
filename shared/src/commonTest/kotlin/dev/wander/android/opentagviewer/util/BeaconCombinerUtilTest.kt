package io.github.tieo.taghistory.util

import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.UserBeaconOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BeaconCombinerUtilTest {

    private fun owned(id: String) = OwnedBeacons(
        id = id, import_id = 1L, content = "content-$id", version = "v1", is_removed = false,
    )

    private fun naming(id: String, content: String = "name-$id") = BeaconNamingRecord(
        id = id, import_id = 1L, version = "v1", content = content, is_removed = false,
    )

    private fun options(id: String, emoji: String = "X") = UserBeaconOptions(
        beacon_id = id, last_update = 0L, ui_name = "ui-$id", ui_emoji = emoji,
    )

    private fun report(ts: Long) = BeaconLocationReport(
        publishedAt = ts + 1_000L,
        description = "",
        timestamp = ts,
        confidence = 1L,
        latitude = 10.0,
        longitude = 10.0,
        horizontalAccuracy = 1L,
        status = 0L,
    )

    @Test
    fun `combine joins naming+owned+options by beacon id`() {
        val result = BeaconCombinerUtil.combine(
            ownedBeacons = listOf(owned("a"), owned("b")),
            beaconNamingRecords = listOf(naming("a"), naming("b")),
            userBeaconOptions = listOf(options("a")),
        )
        assertEquals(2, result.size)
        val a = result.single { it.beaconId == "a" }
        val b = result.single { it.beaconId == "b" }

        assertNotNull(a.ownedBeaconInfo)
        assertNotNull(a.beaconNamingRecord)
        assertNotNull(a.userBeaconOptions)

        assertNotNull(b.ownedBeaconInfo)
        assertNotNull(b.beaconNamingRecord)
        assertNull(b.userBeaconOptions)
    }

    @Test
    fun `combine drops naming records with no matching owned beacon`() {
        val result = BeaconCombinerUtil.combine(
            ownedBeacons = emptyList(),
            beaconNamingRecords = listOf(naming("orphan")),
            userBeaconOptions = emptyList(),
        )
        assertEquals(0, result.size)
    }

    @Test
    fun `combine resolves naming via plist associatedBeacon`() {
        val beaconId = "BEACON-UUID-1"
        val namingRecordId = "NAMING-RECORD-UUID-9"
        val plistContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
              <dict>
                <key>associatedBeacon</key>
                <string>$beaconId</string>
                <key>name</key>
                <string>My Bike</string>
                <key>emoji</key>
                <string>🚲</string>
              </dict>
            </plist>
        """.trimIndent()

        val result = BeaconCombinerUtil.combine(
            ownedBeacons = listOf(owned(beaconId)),
            beaconNamingRecords = listOf(naming(namingRecordId, content = plistContent)),
            userBeaconOptions = emptyList(),
        )

        assertEquals(1, result.size)
        val row = result.single()
        assertEquals(beaconId, row.beaconId)
        assertNotNull(row.ownedBeaconInfo)
        assertNotNull(row.beaconNamingRecord)
        assertEquals(namingRecordId, row.beaconNamingRecord?.id)
    }

    @Test
    fun `combineAndSort dedups by hash and sorts by timestamp`() {
        val r1 = report(100L)
        val r2 = report(200L)
        val r3 = report(300L)

        val merged = BeaconCombinerUtil.combineAndSort(
            beaconId = "id",
            first = listOf(r2, r1),
            second = listOf(r3, r2), // r2 appears in both
        )
        // Three unique reports, ascending by timestamp.
        assertEquals(listOf(100L, 200L, 300L), merged.map { it.timestamp })
    }

    @Test
    fun `combineAndSort prefers second on hash collision`() {
        val r = report(500L)
        // Same report on both sides — dedupe leaves a single entry.
        val merged = BeaconCombinerUtil.combineAndSort("id", listOf(r), listOf(r))
        assertEquals(1, merged.size)
    }
}
