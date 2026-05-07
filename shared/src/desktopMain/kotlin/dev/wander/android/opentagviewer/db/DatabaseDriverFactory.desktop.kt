package io.github.tieo.taghistory.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Desktop driver factory. Defaults to in-memory for ease of dev; callers
 * can pass a `jdbc:sqlite:<path>` string to persist to disk.
 */
actual class DatabaseDriverFactory(
    private val jdbcUrl: String = JdbcSqliteDriver.IN_MEMORY,
) {
    actual fun create(): SqlDriver {
        val driver = JdbcSqliteDriver(jdbcUrl)
        TagHistoryDatabase.Schema.create(driver)
        return driver
    }
}
