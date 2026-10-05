package io.github.tieo.taghistory.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/**
 * Desktop driver factory. Defaults to in-memory for the dev harness and
 * tests; the server passes a `jdbc:sqlite:<path>` URL to persist to disk.
 *
 * A file database outlives the code that created it, so the schema version
 * is kept in `PRAGMA user_version`, the same place the Android driver keeps
 * it: a new file gets the full schema, an older one runs the numbered
 * migrations up to [TagHistoryDatabase.Schema.version].
 */
actual class DatabaseDriverFactory(
    private val jdbcUrl: String = JdbcSqliteDriver.IN_MEMORY,
) {
    actual suspend fun create(): SqlDriver {
        // Foreign keys stay off, as on Android: the upserts are INSERT OR
        // REPLACE, which deletes the old row first, and with enforcement on
        // that delete would cascade from OwnedBeacons into every stored
        // LocationReport of a re-imported beacon.
        val driver = JdbcSqliteDriver(jdbcUrl, Properties())
        migrate(driver)
        return driver
    }

    private fun migrate(driver: SqlDriver) {
        val schema = TagHistoryDatabase.Schema
        val current = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            parameters = 0,
        ).value
        when {
            current == 0L -> schema.create(driver)
            current < schema.version -> schema.migrate(driver, current, schema.version)
            else -> return
        }
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
    }
}
