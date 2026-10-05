package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.anisette.AnisetteProvider
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.createDatabase
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Two beacons in the shape of a real OpenTagViewer export. */
object Fixtures {
    const val BEACON_A = "6981B29C-39AB-46DB-8428-ADB6DF341F80"
    const val BEACON_B = "7135DA28-0771-498A-92EB-8F4FCF32C2C2"
    private const val RECORD_A = "A692E30D-1EE7-40BF-85FE-86C0D042E337"
    private const val RECORD_B = "7EC03AE9-2A69-4FA5-A358-076BF8E07BD5"

    /** Base64 of the bytes "SECRET-KEY-MATERIAL", recognisable in any output. */
    const val SECRET_B64 = "U0VDUkVULUtFWS1NQVRFUklBTA=="

    fun ownedPlist(id: String) = """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
<key>identifier</key><string>$id</string>
<key>model</key><string>AirTag1,1</string>
<key>pairingDate</key><date>2026-05-14T15:29:43Z</date>
<key>privateKey</key><dict><key>key</key><dict><key>data</key><data>$SECRET_B64</data></dict></dict>
<key>sharedSecret</key><dict><key>key</key><dict><key>data</key><data>$SECRET_B64</data></dict></dict>
<key>secondarySharedSecret</key><dict><key>key</key><dict><key>data</key><data>$SECRET_B64</data></dict></dict>
<key>productId</key><integer>21760</integer>
</dict></plist>"""

    fun namingPlist(beaconId: String, record: String, name: String) = """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
<key>associatedBeacon</key><string>$beaconId</string>
<key>identifier</key><string>$record</string>
<key>name</key><string>$name</string>
<key>emoji</key><string>🔑</string>
</dict></plist>"""

    fun exportZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
            put("OPENTAGVIEWER.yml", "version: \"1.0.0\"\nexportTimestamp: 1780000000000\nvia: test\n")
            put("OwnedBeacons/$BEACON_A.plist", ownedPlist(BEACON_A))
            put("OwnedBeacons/$BEACON_B.plist", ownedPlist(BEACON_B))
            put("BeaconNamingRecord/$BEACON_A/$RECORD_A.plist", namingPlist(BEACON_A, RECORD_A, "Car keys"))
            put("BeaconNamingRecord/$BEACON_B/$RECORD_B.plist", namingPlist(BEACON_B, RECORD_B, "Backpack"))
        }
        return out.toByteArray()
    }
}

/** Anisette is only reached by sign-in and sync, which these tests stub out. */
object UnusedAnisette : AnisetteProvider {
    override suspend fun getHeaders(): Map<String, String> = error("anisette must not be reached")
    override suspend fun version(): String = error("anisette must not be reached")
}

object UnusedHttp : HttpTransport {
    override suspend fun execute(request: io.github.tieo.taghistory.apple.http.HttpRequest) =
        error("Apple must not be reached")
}

class ServerFixture(
    val dir: File = Files.createTempDirectory("taghistory-server-test").toFile(),
    var now: Long = 1_790_000_000_000L,
) {
    val db: TagHistoryDatabase = runBlocking {
        createDatabase(DatabaseDriverFactory("jdbc:sqlite:${File(dir, "server.db").absolutePath}"))
    }
    val keyFile = File(dir, "blob.key").apply { writeBytes(ByteArray(32) { it.toByte() }) }
    val server = TagHistoryServer(
        db = db,
        settings = fileSettings(File(dir, "settings.properties")),
        blobStore = SecureBlobStore(keyFile),
        anisetteProvider = UnusedAnisette,
        http = UnusedHttp,
        syncIntervalMinutes = 30,
        geocoder = null,
        nowMs = { now },
    )
}
