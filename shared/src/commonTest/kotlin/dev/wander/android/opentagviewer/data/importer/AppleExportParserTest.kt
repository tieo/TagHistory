package io.github.tieo.taghistory.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AppleExportParserTest {

    private val sampleYaml = """
        version: "1"
        exportTimestamp: 1700000000
        sourceUser: "test@example.com"
        via: "unit-test 0.1"
    """.trimIndent()

    private val beaconId1 = "A1B2C3D4-E5F6-4789-8ABC-DEF012345678"
    private val beaconId2 = "11111111-2222-4333-8444-555555555555"
    private val namingRecId1 = "00000000-1111-4222-8333-444444444444"

    @Test
    fun `happy path — owned + naming pair becomes an import`() {
        val entries = mapOf(
            "OPENTAGVIEWER.yml" to sampleYaml.encodeToByteArray(),
            "OwnedBeacons/$beaconId1.plist" to "<plist>owned</plist>".encodeToByteArray(),
            "BeaconNamingRecord/$beaconId1/$namingRecId1.plist" to
                "<plist>naming</plist>".encodeToByteArray(),
        )

        val result = AppleExportParser.parse(entries, nowMs = 999L)
        when (result) {
            is AppleExportParser.ParseResult.Ok -> {
                assertEquals(1, result.imported)
                assertEquals("1", result.data.anImport.version)
                assertEquals(1700000000L, result.data.anImport.exported_at)
                assertEquals("test@example.com", result.data.anImport.source_user)
                assertEquals("unit-test 0.1", result.data.anImport.via)
                assertEquals(999L, result.data.anImport.imported_at)
                assertEquals(1, result.data.ownedBeacons.size)
                assertEquals(beaconId1, result.data.ownedBeacons[0].id)
                assertEquals(1, result.data.beaconNamingRecords.size)
                assertEquals(namingRecId1, result.data.beaconNamingRecords[0].id)
            }
            is AppleExportParser.ParseResult.Err -> fail("Expected Ok, got ${result.message}")
        }
    }

    @Test
    fun `missing yaml is an error`() {
        val entries = mapOf(
            "OwnedBeacons/$beaconId1.plist" to "x".encodeToByteArray(),
            "BeaconNamingRecord/$beaconId1/$namingRecId1.plist" to "x".encodeToByteArray(),
        )
        val result = AppleExportParser.parse(entries)
        assertTrue(result is AppleExportParser.ParseResult.Err)
        assertTrue(result.message.contains("OPENTAGVIEWER.yml"))
    }

    @Test
    fun `inner-join drops owned-without-naming and naming-without-owned`() {
        // Two owned beacons, only one has a naming record.
        val entries = mapOf(
            "OPENTAGVIEWER.yml" to sampleYaml.encodeToByteArray(),
            "OwnedBeacons/$beaconId1.plist" to "a".encodeToByteArray(),
            "OwnedBeacons/$beaconId2.plist" to "b".encodeToByteArray(),
            "BeaconNamingRecord/$beaconId1/$namingRecId1.plist" to "n1".encodeToByteArray(),
        )
        val result = AppleExportParser.parse(entries)
        result as AppleExportParser.ParseResult.Ok
        assertEquals(1, result.imported)
        assertEquals(beaconId1, result.data.ownedBeacons.single().id)
    }

    @Test
    fun `archive with no matching pairs rejects with clear message`() {
        val entries = mapOf(
            "OPENTAGVIEWER.yml" to sampleYaml.encodeToByteArray(),
            "OwnedBeacons/$beaconId1.plist" to "a".encodeToByteArray(),
            "BeaconNamingRecord/$beaconId2/$namingRecId1.plist" to "n1".encodeToByteArray(),
        )
        val result = AppleExportParser.parse(entries)
        assertTrue(result is AppleExportParser.ParseResult.Err)
    }

    @Test
    fun `garbage entries outside the schema are ignored`() {
        val entries = mapOf(
            "OPENTAGVIEWER.yml" to sampleYaml.encodeToByteArray(),
            "OwnedBeacons/$beaconId1.plist" to "owned".encodeToByteArray(),
            "BeaconNamingRecord/$beaconId1/$namingRecId1.plist" to "naming".encodeToByteArray(),
            "README.md" to "# ignore me".encodeToByteArray(),
            "__MACOSX/junk" to ByteArray(10),
            "OwnedBeacons/not-a-uuid.plist" to "ignored".encodeToByteArray(),
        )
        val result = AppleExportParser.parse(entries)
        result as AppleExportParser.ParseResult.Ok
        assertEquals(1, result.imported)
    }

    @Test
    fun `yaml parser accepts unquoted values and different key orders`() {
        val yaml = """
            # A comment line
            via: python-wizard
            exportTimestamp: 42
            version: 2
            sourceUser: nobody
        """.trimIndent()
        val entries = mapOf(
            "OPENTAGVIEWER.yml" to yaml.encodeToByteArray(),
            "OwnedBeacons/$beaconId1.plist" to "o".encodeToByteArray(),
            "BeaconNamingRecord/$beaconId1/$namingRecId1.plist" to "n".encodeToByteArray(),
        )
        val result = AppleExportParser.parse(entries)
        result as AppleExportParser.ParseResult.Ok
        assertEquals("2", result.data.anImport.version)
        assertEquals(42L, result.data.anImport.exported_at)
        assertEquals("python-wizard", result.data.anImport.via)
        assertEquals("nobody", result.data.anImport.source_user)
    }
}
