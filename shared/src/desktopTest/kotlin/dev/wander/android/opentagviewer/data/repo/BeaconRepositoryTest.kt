package io.github.tieo.taghistory.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.ImportData
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.DailyHistoryFetchRecord
import io.github.tieo.taghistory.db.Import
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.UserBeaconOptions
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration test against an in-memory JDBC SQLite database — exercises
 * the generated SQLDelight queries plus the repository's transaction +
 * mapping logic. Avoids mocking the DB so migration-breaking schema
 * changes fail the build here rather than on device.
 */
class BeaconRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: TagHistoryDatabase
    private lateinit var repo: BeaconRepository

    private var now: Long = 1_700_000_000_000L

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        db = TagHistoryDatabase(driver)
        repo = BeaconRepository(db) { now }
    }

    private fun importRow(sourceUser: String = "me@example.com") = Import(
        id = 0L, // ignored on insert (AUTOINCREMENT)
        version = "v1",
        imported_at = 1L,
        exported_at = 2L,
        source_user = sourceUser,
        via = "unit-test",
    )

    private fun ownedRow(id: String) = OwnedBeacons(
        id = id, import_id = 0L, content = "content-$id", version = "v1", is_removed = false,
    )

    private fun namingRow(id: String) = BeaconNamingRecord(
        id = id, import_id = 0L, version = "v1", content = "name-$id", is_removed = false,
    )

    private fun report(ts: Long, lat: Double = 10.0, lon: Double = 20.0) = BeaconLocationReport(
        publishedAt = ts + 1_000L,
        description = "d",
        timestamp = ts,
        confidence = 1L,
        latitude = lat,
        longitude = lon,
        horizontalAccuracy = 1L,
        status = 0L,
    )

    @Test
    fun `addNewImport assigns generated id and cascades to child rows`() {
        val imported = repo.addNewImport(
            ImportData(
                anImport = importRow(),
                ownedBeacons = listOf(ownedRow("beacon-1"), ownedRow("beacon-2")),
                beaconNamingRecords = listOf(namingRow("beacon-1"), namingRow("beacon-2")),
            ),
        )
        assertNotNull(imported.anImport.id)
        val newId = imported.anImport.id
        assertEquals(newId, imported.ownedBeacons.first().import_id)
        assertEquals(newId, imported.beaconNamingRecords.first().import_id)

        val stored = assertNotNull(repo.getImportById(newId))
        assertEquals("me@example.com", stored.source_user)
    }

    @Test
    fun `getAllBeacons joins naming+owned+user-options`() {
        repo.addNewImport(
            ImportData(
                anImport = importRow(),
                ownedBeacons = listOf(ownedRow("b1"), ownedRow("b2")),
                beaconNamingRecords = listOf(namingRow("b1"), namingRow("b2")),
            ),
        )
        repo.storeUserBeaconOptions(
            UserBeaconOptions(
                beacon_id = "b1", last_update = 10L, ui_name = "Mine", ui_emoji = "X",
            ),
        )
        val all = repo.getAllBeacons().associateBy { it.beaconId }
        assertEquals(2, all.size)
        assertEquals("Mine", all.getValue("b1").userBeaconOptions?.ui_name)
        assertNull(all.getValue("b2").userBeaconOptions)
    }

    @Test
    fun `storeToLocationCache dedupes by content hash and getLastLocations returns max timestamp`() {
        repo.addNewImport(
            ImportData(
                anImport = importRow(),
                ownedBeacons = listOf(ownedRow("b1")),
                beaconNamingRecords = listOf(namingRow("b1")),
            ),
        )
        val r1 = report(1_000L)
        val r2 = report(2_000L)
        val r3 = report(1_500L, lat = 11.0) // unique content — different hash

        repo.storeToLocationCache(mapOf("b1" to listOf(r1, r2)))
        // second call includes a duplicate of r1 — hashed upsert → same
        // row, and adds a new unique r3.
        repo.storeToLocationCache(mapOf("b1" to listOf(r1, r3)))

        val inRange = repo.getLocationsFor("b1", 0L, 10_000L)
        assertEquals(3, inRange.size)
        assertEquals(listOf(1_000L, 1_500L, 2_000L), inRange.map { it.timestamp })

        val last = repo.getLastLocationsForAll()
        assertEquals(2_000L, last.getValue("b1").timestamp)
    }

    @Test
    fun `getLocationsFor filters by time window`() {
        repo.addNewImport(
            ImportData(
                anImport = importRow(),
                ownedBeacons = listOf(ownedRow("b")),
                beaconNamingRecords = listOf(namingRow("b")),
            ),
        )
        repo.storeToLocationCache(
            mapOf("b" to listOf(report(100L), report(500L), report(900L))),
        )
        val mid = repo.getLocationsFor("b", 200L, 800L)
        assertEquals(listOf(500L), mid.map { it.timestamp })
    }

    @Test
    fun `storeHistoryRecords stamps lastUpdate and is retrievable`() {
        now = 12345L
        val recs = arrayOf(
            DailyHistoryFetchRecord(
                day_start_time = 1_699_000_000_000L,
                beacon_id = "b1",
                last_update = 0L,
            ),
        )
        val stamped = repo.storeHistoryRecords(*recs)
        assertEquals(12345L, stamped.single().last_update)

        val got = assertNotNull(repo.getInsertionHistoryItem("b1", 1_699_000_000_000L))
        assertEquals(12345L, got.last_update)
        assertNull(repo.getInsertionHistoryItem("b1", 0L))
    }

    @Test
    fun `markBeaconAsRemoved hides rows from getAllBeacons and getById`() {
        repo.addNewImport(
            ImportData(
                anImport = importRow(),
                ownedBeacons = listOf(ownedRow("gone"), ownedRow("kept")),
                beaconNamingRecords = listOf(namingRow("gone"), namingRow("kept")),
            ),
        )
        repo.markBeaconAsRemoved("gone")

        // `getAll`/`getById` explicitly filter `is_removed = 0`, so a
        // removed beacon must disappear from both lookup paths.
        val visible = repo.getAllBeacons().map { it.beaconId }
        assertEquals(listOf("kept"), visible)
        assertNull(repo.getById("gone"))
        assertNotNull(repo.getById("kept"))
    }
}
