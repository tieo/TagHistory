package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.createDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The standalone app and the server trade reports both ways: each ends with
 * the union, a report both fetched is stored once, and nothing received is
 * sent straight back.
 */
class ReportExchangeTest {

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

    private class Phone {
        val db = runBlocking { createDatabase(DatabaseDriverFactory()) }
        val repo = BeaconRepository(db)
        val cursors = fileSettings(File(Files.createTempDirectory("exchange").toFile(), "c.properties"))
        val exchange = ServerReportExchange(db, cursors)

        fun timestamps(beaconId: String) = repo.getLocationsFor(beaconId, 0, Long.MAX_VALUE).map { it.timestamp }.sorted()
    }

    /** Runs [block] against [f]'s HTTP API, counting the report uploads the client sends. */
    private suspend fun <T> withServer(f: ServerFixture, block: suspend (ServerClient, () -> Int) -> T): T {
        val app = TestApplication { application { tagHistoryModule(f.server, webDir = null) } }
        app.start()
        var uploads = 0
        // Installed, not intercepted: ServerClient derives its own client
        // from this one, which keeps plugins but not interceptors.
        val counter = createClientPlugin("UploadCounter") {
            onRequest { request, _ ->
                if (request.method == HttpMethod.Post && request.url.build().encodedPath == ServerApi.REPORTS) uploads++
            }
        }
        val http: HttpClient = app.createClient { install(counter) }
        return try {
            block(ServerClient("", http)) { uploads }
        } finally {
            app.stop()
        }
    }

    @Test
    fun both_sides_end_with_the_union_and_nothing_echoes_back() = runBlocking<Unit> {
        val f = ServerFixture()
        f.server.import(Fixtures.exportZip())
        val shared = report(1_780_000_000_000L, 48.30)
        val serverOnly = report(1_780_000_060_000L, 48.31)
        val phoneOnly = listOf(report(1_780_000_120_000L, 48.32), report(1_780_000_180_000L, 48.33))
        f.server.beaconRepo.storeToLocationCache(mapOf(Fixtures.BEACON_A to listOf(shared, serverOnly)))
        val phone = Phone()
        phone.repo.storeToLocationCache(mapOf(Fixtures.BEACON_A to listOf(shared) + phoneOnly))

        val first = withServer(f) { client, _ -> phone.exchange.exchange(client) }

        assertEquals(2, first.uploaded, "only the phone's own reports are new to the server")
        assertEquals(1, first.downloaded, "only the server's own report is new to the phone")
        val union = (listOf(shared, serverOnly) + phoneOnly).map { it.timestamp }.sorted()
        assertEquals(union, phone.timestamps(Fixtures.BEACON_A))
        assertEquals(union, f.server.beaconRepo.getLocationsFor(Fixtures.BEACON_A, 0, Long.MAX_VALUE).map { it.timestamp }.sorted())

        // What the phone received is not sent back on the next round.
        withServer(f) { client, uploads ->
            val second = phone.exchange.exchange(client)
            assertEquals(ServerReportExchange.Result(0, 0), second)
            assertEquals(0, uploads())
        }

        // A report the phone fetches later still goes up.
        phone.repo.storeToLocationCache(mapOf(Fixtures.BEACON_A to listOf(report(1_780_000_240_000L, 48.34))))
        withServer(f) { client, uploads ->
            assertEquals(1, phone.exchange.exchange(client).uploaded)
            assertEquals(1, uploads())
        }
    }

    @Test
    fun a_new_server_database_receives_the_whole_phone_history() = runBlocking<Unit> {
        val phone = Phone()
        val history = (1..5).map { report(1_780_000_000_000L + it * 60_000L, 48.0 + it / 100.0) }
        phone.repo.storeToLocationCache(mapOf(Fixtures.BEACON_A to history))
        val first = ServerFixture().apply { server.import(Fixtures.exportZip()) }
        withServer(first) { client, _ -> assertEquals(5, phone.exchange.exchange(client).uploaded) }

        // The server lost its database: a fresh one gets everything again.
        val replacement = ServerFixture().apply { server.import(Fixtures.exportZip()) }
        withServer(replacement) { client, _ -> assertEquals(5, phone.exchange.exchange(client).uploaded) }
        assertEquals(
            history.map { it.timestamp },
            replacement.server.beaconRepo.getLocationsFor(Fixtures.BEACON_A, 0, Long.MAX_VALUE).map { it.timestamp }.sorted(),
        )
    }
}
