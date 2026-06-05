package io.github.tieo.taghistory.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific SQL driver construction. Android uses
 * `AndroidSqliteDriver` (SQLite bundled by the Android platform); desktop
 * uses `JdbcSqliteDriver` with the `sqlite-jdbc` bundle; iOS uses
 * `NativeSqliteDriver`.
 *
 * Each platform's [create] returns a driver that's already schema-migrated
 * — callers just wrap it with [TagHistoryDatabase] and go.
 */
expect class DatabaseDriverFactory {
    suspend fun create(): SqlDriver
}

/** Build a ready-to-use database from the factory. */
suspend fun createDatabase(factory: DatabaseDriverFactory): TagHistoryDatabase =
    TagHistoryDatabase(factory.create())
