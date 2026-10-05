package io.github.tieo.taghistory.server

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.TagHistoryDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A file database written by an older schema must be migrated, not
 * re-created: re-creating would leave tables added later missing, and the
 * first query against one fails at runtime on the server.
 */
class DesktopDatabaseMigrationTest {

    private fun userVersion(url: String): Long = JdbcSqliteDriver(url).let { d ->
        d.executeQuery(null, "PRAGMA user_version", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value!!
            .also { d.close() }
    }

    @Test
    fun a_version_1_file_gains_the_later_tables_and_keeps_its_rows() = runBlocking<Unit> {
        val file = File(Files.createTempDirectory("db").toFile(), "old.db")
        val url = "jdbc:sqlite:${file.absolutePath}"
        // Version 1 had no GeocodeCache and no SyncRunRecord.
        JdbcSqliteDriver(url).apply {
            execute(null, "CREATE TABLE OwnedBeacons (id TEXT NOT NULL PRIMARY KEY, import_id INTEGER, content TEXT, version TEXT, is_removed INTEGER NOT NULL DEFAULT 0)", 0)
            execute(null, "INSERT INTO OwnedBeacons(id, is_removed) VALUES ('kept', 0)", 0)
            execute(null, "PRAGMA user_version = 1", 0)
            close()
        }

        val db = TagHistoryDatabase(DatabaseDriverFactory(url).create())

        assertEquals(listOf("kept"), db.ownedBeaconQueries.getAll().executeAsList().map { it.id })
        assertEquals(0L, db.syncRunRecordQueries.recent(10).executeAsList().size.toLong())
        assertEquals(TagHistoryDatabase.Schema.version, userVersion(url))
    }

    @Test
    fun a_new_file_is_created_at_the_current_version_and_reopens() = runBlocking<Unit> {
        val file = File(Files.createTempDirectory("db").toFile(), "new.db")
        val url = "jdbc:sqlite:${file.absolutePath}"

        TagHistoryDatabase(DatabaseDriverFactory(url).create()).ownedBeaconQueries
            .upsert("a", null, null, null, false)
        val reopened = TagHistoryDatabase(DatabaseDriverFactory(url).create())

        assertEquals(listOf("a"), reopened.ownedBeaconQueries.getAll().executeAsList().map { it.id })
        assertEquals(TagHistoryDatabase.Schema.version, userVersion(url))
    }
}
