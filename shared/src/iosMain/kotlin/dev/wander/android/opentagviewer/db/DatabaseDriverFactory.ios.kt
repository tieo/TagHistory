package io.github.tieo.taghistory.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DatabaseDriverFactory {
    actual suspend fun create(): SqlDriver =
        NativeSqliteDriver(
            schema = TagHistoryDatabase.Schema,
            name = DATABASE_NAME,
        )

    companion object {
        const val DATABASE_NAME: String = "taghistory-db"
    }
}
