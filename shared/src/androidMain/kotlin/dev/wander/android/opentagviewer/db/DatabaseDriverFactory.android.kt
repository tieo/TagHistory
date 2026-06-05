package io.github.tieo.taghistory.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(private val context: Context) {
    actual suspend fun create(): SqlDriver =
        AndroidSqliteDriver(
            schema = TagHistoryDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
        )

    companion object {
        /** Matches the Room filename used by Java — in-place upgrade. */
        const val DATABASE_NAME: String = "taghistory-db"
    }
}
