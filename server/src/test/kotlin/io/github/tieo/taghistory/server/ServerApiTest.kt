package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.data.importer.BeaconInformationParser
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.data.storage.SecureBlobStoreException
import io.github.tieo.taghistory.db.OwnedBeacons
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerApiTest {

    @Test
    fun beacons_endpoint_never_carries_key_material() = testApplication {
        val f = ServerFixture()
        application { tagHistoryModule(f.server, webDir = null) }
        f.server.import(Fixtures.exportZip())

        val body = client.get(ServerApi.BEACONS).bodyAsText()

        assertFalse(Fixtures.SECRET_B64 in body, "a key leaked into the beacons response")
        assertFalse("sharedSecret" in body)
        assertFalse("secondarySharedSecret" in body)
        // What clients display still survives the redaction.
        val snapshot = ServerClient.WireJson.decodeFromString(BeaconsSnapshot.serializer(), body)
        val owned = snapshot.owned.single { it.id == Fixtures.BEACON_A }
        val info = BeaconInformationParser.parse(
            BeaconData(owned.id, OwnedBeacons(owned.id, owned.importId, owned.content, owned.version, false), null, null),
        )
        assertNotNull(info)
        assertEquals("AirTag1,1", info.model)
        assertEquals(21760, info.productId)
        assertTrue(info.hasPrivateKey)
    }

    @Test
    fun redaction_keeps_only_listed_fields() {
        val redacted = KeyRedaction.redactOwnedBeacon(Fixtures.ownedPlist(Fixtures.BEACON_A))!!
        val dict = XmlPlist.parse(redacted) as PlistValue.Dict
        assertEquals(
            setOf("identifier", "model", "pairingDate", "privateKey", "productId"),
            dict.entries.keys,
        )
        assertEquals(0, dict.data("privateKey")?.size)
    }

    @Test
    fun renaming_an_unknown_beacon_is_rejected_and_stores_nothing() = testApplication {
        val f = ServerFixture()
        application { tagHistoryModule(f.server, webDir = null) }
        f.server.import(Fixtures.exportZip())
        val unknown = "00000000-0000-4000-8000-000000000000"

        val response = client.put(ServerApi.beacon(unknown)) {
            contentType(ContentType.Application.Json)
            setBody("""{"beaconId":"$unknown","lastUpdate":1,"uiName":"x","uiEmoji":null}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(f.server.beacons().options.isEmpty())
    }

    @Test
    fun rename_reaches_every_client_through_the_snapshot() = testApplication {
        val f = ServerFixture()
        application { tagHistoryModule(f.server, webDir = null) }
        f.server.import(Fixtures.exportZip())

        val response = client.put(ServerApi.beacon(Fixtures.BEACON_A)) {
            contentType(ContentType.Application.Json)
            setBody("""{"beaconId":"${Fixtures.BEACON_A}","lastUpdate":1,"uiName":"S-Max keys","uiEmoji":"🚗"}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val options = f.server.beacons().options.single()
        assertEquals("S-Max keys", options.uiName)
        assertEquals("🚗", options.uiEmoji)
        // The server stamps the write itself; a client clock is not trusted.
        assertEquals(f.now, options.lastUpdate)
    }

    @Test
    fun a_broken_import_is_a_client_error_and_imports_nothing() = runBlocking<Unit> {
        val f = ServerFixture()
        val notAnExport = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("readme.txt")) }
        }.toByteArray()

        assertFailsWith<IllegalArgumentException> { f.server.import(notAnExport) }
        assertTrue(f.server.beacons().owned.isEmpty())
    }

    @Test
    fun blob_store_round_trips_and_rejects_another_key() {
        val dir = Files.createTempDirectory("blob").toFile()
        val keyA = File(dir, "a").apply { writeText(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })) }
        val keyB = File(dir, "b").apply { writeBytes(ByteArray(32) { 2 }) }
        val plaintext = "apple session".encodeToByteArray()

        val envelope = SecureBlobStore(keyA).encrypt(plaintext, "alias")

        assertFalse(envelope.decodeToString().contains("apple session"))
        assertTrue(SecureBlobStore(keyA).decrypt(envelope, "alias").contentEquals(plaintext))
        assertFailsWith<SecureBlobStoreException> { SecureBlobStore(keyB).decrypt(envelope, "alias") }
        // The alias is bound in: a blob cannot be replayed under another name.
        assertFailsWith<SecureBlobStoreException> { SecureBlobStore(keyA).decrypt(envelope, "other") }
    }

    @Test
    fun status_reports_the_stored_session_and_counts() = testApplication {
        val f = ServerFixture()
        application { tagHistoryModule(f.server, webDir = null) }
        f.server.import(Fixtures.exportZip())

        val status = ServerClient("", createClient { }).status()

        assertFalse(status.signedIn)
        assertEquals(2, status.beaconCount)
        assertEquals(0, status.reportCount)
        assertEquals(f.server.instanceId, status.instanceId)
    }

    @Test
    fun uploaded_reports_are_stored_once_and_unknown_beacons_skipped() = testApplication {
        val f = ServerFixture()
        application { tagHistoryModule(f.server, webDir = null) }
        f.server.import(Fixtures.exportZip())
        val client = ServerClient("", createClient { })
        fun upload(beaconId: String, timestamp: Long) = ReportUpload(
            beaconId = beaconId, publishedAt = 0, description = null, timestamp = timestamp,
            confidence = 0, latitude = 48.1, longitude = 11.5, horizontalAccuracy = 20, status = 0,
        )
        val batch = listOf(
            upload(Fixtures.BEACON_A, 1_000),
            upload(Fixtures.BEACON_A, 2_000),
            upload("00000000-0000-4000-8000-000000000000", 1_000),
        )

        assertEquals(2, client.uploadReports(batch).stored)
        val firstPage = client.reports(after = 0)
        assertEquals(0, client.uploadReports(batch).stored)

        val page = client.reports(after = 0)
        // A repeated upload leaves the rows in place, so nothing replicates again.
        assertEquals(firstPage.next, page.next)
        assertEquals(listOf(1_000L, 2_000L), page.reports.map { it.timestamp }.sorted())
        assertTrue(page.reports.all { it.beaconId == Fixtures.BEACON_A })
    }

    @Test
    fun oauth_callback_hands_the_code_to_the_app() = testApplication {
        application { tagHistoryModule(ServerFixture().server, webDir = null) }

        val response = client.post(ServerApi.OAUTH2_CALLBACK) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("code=a%2Bb&state=s1&iss=https%3A%2F%2Fauth.example&scope=x")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(
            "href=\"${ServerApi.APP_REDIRECT}?code=a%2Bb&amp;state=s1&amp;iss=https%3A%2F%2Fauth.example\"" in body,
            body,
        )
    }
}
