package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.createDatabase
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replication contract: a client that pulls repeatedly ends up with exactly
 * the server's rows, receives each write once per write, and survives the
 * server's database being replaced.
 */
class ReplicationTest {

    private fun report(timestamp: Long, lat: Double) = BeaconLocationReport(
        publishedAt = timestamp + 1_000,
        description = "found",
        timestamp = timestamp,
        confidence = 2,
        latitude = lat,
        longitude = 9.71,
        horizontalAccuracy = 40,
        status = 0,
    )

    @Test
    fun pages_cover_every_row_once_in_write_order() = runBlocking<Unit> {
        val f = ServerFixture()
        f.server.import(Fixtures.exportZip())
        val reports = (1..7).map { report(1_780_000_000_000L + it * 60_000L, 48.0 + it / 100.0) }
        f.server.beaconRepo.storeToLocationCache(mapOf(Fixtures.BEACON_A to reports))

        val seen = mutableListOf<ReportDto>()
        var after = 0L
        var pages = 0
        do {
            val page = f.server.reports(after, limit = 3)
            seen += page.reports
            after = page.next
            pages++
        } while (page.hasMore)

        // 7 rows in pages of 3 is 3 pages: 3 + 3 + 1.
        assertEquals(3, pages)
        assertEquals(reports.map { it.timestamp }.sorted(), seen.map { it.timestamp }.sorted())
        assertEquals(seen.size, seen.map { it.hashId }.toSet().size, "a row was sent twice")
        assertEquals(seen.map { it.seq }.sorted(), seen.map { it.seq }, "pages must be in seq order")
        // Nothing after the last cursor.
        assertTrue(f.server.reports(after, limit = 3).reports.isEmpty())
    }

    @Test
    fun a_rewritten_row_is_sent_again_after_the_cursor() = runBlocking<Unit> {
        val f = ServerFixture()
        f.server.import(Fixtures.exportZip())
        val r = report(1_780_000_000_000L, 48.29)
        f.server.beaconRepo.storeToLocationCache(mapOf(Fixtures.BEACON_A to listOf(r)))
        val first = f.server.reports(0, 100)
        assertEquals(1, first.reports.size)

        // An overlapping sync window stores the same report again.
        f.now += 3_600_000L
        f.server.beaconRepo.storeToLocationCache(mapOf(Fixtures.BEACON_A to listOf(r)))

        val second = f.server.reports(first.next, 100)
        assertEquals(1, second.reports.size, "the rewrite must reach clients past the old cursor")
        assertEquals(f.now, second.reports.single().lastUpdate)
    }

    @Test
    fun client_replica_matches_the_server_through_http() = testApplication {
        val f = ServerFixture()
        application { tagHistoryModule(f.server, webDir = null) }
        val client = ServerClient(baseUrl = "", engineClient = createClient { })

        val clientDb = createDatabase(DatabaseDriverFactory())
        val clientRepo = BeaconRepository(clientDb)
        val cursors = fileSettings(java.io.File(java.nio.file.Files.createTempDirectory("cursors").toFile(), "c.properties"))
        val replicator = ServerReplicator(client, clientDb, clientRepo, cursors, nowMs = { f.now })

        client.import(Fixtures.exportZip())
        val batch1 = (1..5).map { report(1_780_000_000_000L + it * 60_000L, 48.0 + it / 100.0) }
        f.server.beaconRepo.storeToLocationCache(mapOf(Fixtures.BEACON_A to batch1))

        val pull1 = replicator.pull()
        assertTrue(pull1.beaconsChanged)
        assertEquals(5, pull1.newReports)
        assertEquals(setOf(Fixtures.BEACON_A, Fixtures.BEACON_B), clientRepo.getAllBeacons().map { it.beaconId }.toSet())
        assertEquals("Car keys", clientRepo.getInformationFor(Fixtures.BEACON_A)?.displayName)

        // Nothing new: an idle pull transfers nothing and changes nothing.
        val idle = replicator.pull()
        assertEquals(0, idle.newReports)
        assertFalse(idle.beaconsChanged)

        val batch2 = listOf(report(1_780_000_900_000L, 48.5))
        f.server.beaconRepo.storeToLocationCache(mapOf(Fixtures.BEACON_B to batch2))
        assertEquals(1, replicator.pull().newReports)

        val expected = f.server.beaconRepo.getLocationsFor(Fixtures.BEACON_A, 0, Long.MAX_VALUE) +
            f.server.beaconRepo.getLocationsFor(Fixtures.BEACON_B, 0, Long.MAX_VALUE)
        val actual = clientRepo.getLocationsFor(Fixtures.BEACON_A, 0, Long.MAX_VALUE) +
            clientRepo.getLocationsFor(Fixtures.BEACON_B, 0, Long.MAX_VALUE)
        assertEquals(expected, actual)
    }

    @Test
    fun a_new_server_database_restarts_replication() = runBlocking<Unit> {
        val cursors = fileSettings(java.io.File(java.nio.file.Files.createTempDirectory("cursors").toFile(), "c.properties"))
        val clientDb = createDatabase(DatabaseDriverFactory())
        val repo = BeaconRepository(clientDb)

        suspend fun pullFrom(f: ServerFixture): ServerReplicator.PullResult {
            val app = io.ktor.server.testing.TestApplication {
                application { tagHistoryModule(f.server, webDir = null) }
            }
            app.start()
            return try {
                ServerReplicator(ServerClient("", app.createClient { }), clientDb, repo, cursors, { f.now }).pull()
            } finally {
                app.stop()
            }
        }

        val first = ServerFixture()
        first.server.import(Fixtures.exportZip())
        first.server.beaconRepo.storeToLocationCache(
            mapOf(Fixtures.BEACON_A to (1..4).map { report(1_780_000_000_000L + it * 60_000L, 48.0 + it / 100.0) }),
        )
        assertEquals(4, pullFrom(first).newReports)

        // A replacement database numbers its rows from 1 again. Without the
        // instance check the client's cursor (4) would skip both rows.
        val second = ServerFixture()
        second.server.import(Fixtures.exportZip())
        second.server.beaconRepo.storeToLocationCache(
            mapOf(Fixtures.BEACON_A to (1..2).map { report(1_781_000_000_000L + it * 60_000L, 47.0 + it / 100.0) }),
        )
        assertEquals(2, pullFrom(second).newReports)
    }
}
