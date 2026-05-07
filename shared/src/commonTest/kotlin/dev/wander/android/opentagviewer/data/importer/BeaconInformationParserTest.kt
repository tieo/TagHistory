package io.github.tieo.taghistory.data.importer

import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.UserBeaconOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeaconInformationParserTest {

    private val beaconId = "A1B2C3D4-E5F6-4789-8ABC-DEF012345678"
    private val namingRecId = "00000000-1111-4222-8333-444444444444"

    private fun namingRecordXml(
        name: String? = "My AirTag",
        emoji: String? = "🎒",
        extra: String = "",
    ) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<plist version="1.0"><dict>""")
        append("""<key>identifier</key><string>$namingRecId</string>""")
        append("""<key>associatedBeacon</key><string>$beaconId</string>""")
        if (name != null) append("<key>name</key><string>$name</string>")
        if (emoji != null) append("<key>emoji</key><string>$emoji</string>")
        append(extra)
        append("</dict></plist>")
    }

    private fun ownedBeaconXml(
        model: String? = "AirTag",
        productId: Int? = 21760,
        vendorId: Int? = 76,
        systemVersion: String? = "1.0.276",
        stableId: String? = "STABLE-1",
        pairingDateIso: String? = "2024-06-01T10:20:30Z",
        includePrivateKey: Boolean = true,
    ) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<plist version="1.0"><dict>""")
        if (model != null) append("<key>model</key><string>$model</string>")
        if (productId != null) append("<key>productId</key><integer>$productId</integer>")
        if (vendorId != null) append("<key>vendorId</key><integer>$vendorId</integer>")
        if (systemVersion != null) append("<key>systemVersion</key><string>$systemVersion</string>")
        if (stableId != null) {
            append("<key>stableIdentifier</key><array><string>$stableId</string></array>")
        }
        if (pairingDateIso != null) append("<key>pairingDate</key><date>$pairingDateIso</date>")
        if (includePrivateKey) {
            append("<key>privateKey</key><data>AAAA</data>")
        }
        append("</dict></plist>")
    }

    private fun beaconDataOf(
        owned: String? = ownedBeaconXml(),
        naming: String? = namingRecordXml(),
        options: UserBeaconOptions? = null,
    ) = BeaconData(
        beaconId = beaconId,
        ownedBeaconInfo = owned?.let {
            OwnedBeacons(
                id = beaconId,
                import_id = null,
                content = it,
                version = "1",
                is_removed = false,
            )
        },
        beaconNamingRecord = naming?.let {
            BeaconNamingRecord(
                id = namingRecId,
                import_id = null,
                version = "1",
                content = it,
                is_removed = false,
            )
        },
        userBeaconOptions = options,
    )

    @Test
    fun happy_path_extracts_name_emoji_and_hardware_fields() {
        val info = BeaconInformationParser.parse(beaconDataOf())
        assertNotNull(info)
        assertEquals("My AirTag", info.originalName)
        assertEquals("🎒", info.originalEmoji)
        assertEquals("AirTag", info.model)
        assertEquals(21760, info.productId)
        assertEquals(76, info.vendorId)
        assertEquals("1.0.276", info.systemVersion)
        assertEquals("STABLE-1", info.stableIdentifier)
        assertTrue(info.hasPrivateKey)
        val pairing = info.pairingDate
        assertNotNull(pairing)
        assertTrue(pairing > 1_700_000_000_000L)
    }

    @Test
    fun display_name_prefers_user_override_over_apple_name() {
        val opts = UserBeaconOptions(
            beacon_id = beaconId,
            last_update = 0L,
            ui_name = "Backpack",
            ui_emoji = "🎒",
        )
        val info = BeaconInformationParser.parse(beaconDataOf(options = opts))
        assertNotNull(info)
        assertEquals("Backpack", info.displayName)
        assertEquals("🎒", info.displayEmoji)
    }

    @Test
    fun display_name_falls_back_to_uuid_prefix_when_nothing_else() {
        val info = BeaconInformationParser.parse(
            beaconDataOf(naming = namingRecordXml(name = null, emoji = null)),
        )
        assertNotNull(info)
        assertEquals(beaconId.take(8), info.displayName)
        assertNull(info.displayEmoji)
    }

    @Test
    fun missing_private_key_flagged_but_not_fatal() {
        val info = BeaconInformationParser.parse(
            beaconDataOf(owned = ownedBeaconXml(includePrivateKey = false)),
        )
        assertNotNull(info)
        assertFalse(info.hasPrivateKey)
        // Everything else still comes through.
        assertEquals("AirTag", info.model)
    }

    @Test
    fun returns_null_when_both_plist_blobs_missing() {
        val info = BeaconInformationParser.parse(beaconDataOf(owned = null, naming = null))
        assertNull(info)
    }

    @Test
    fun tolerates_malformed_xml_in_one_blob() {
        // Broken naming record — we should still pull hardware fields.
        val info = BeaconInformationParser.parse(
            beaconDataOf(naming = "<not-a-plist><!!garbage"),
        )
        assertNotNull(info)
        assertNull(info.originalName)
        assertEquals("AirTag", info.model)
    }

}
