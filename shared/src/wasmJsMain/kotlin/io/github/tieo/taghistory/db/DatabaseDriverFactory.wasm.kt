package io.github.tieo.taghistory.db

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.createDefaultWebWorkerDriver

/**
 * wasmJs DatabaseDriverFactory. Uses SqlDelight's bundled
 * `createDefaultWebWorkerDriver()` which instantiates a Web Worker
 * pointing at the sqljs worker script that ships in the
 * `@cashapp/sqldelight-sqljs-worker` npm package, then awaits
 * `Schema.create` so the returned SqlDriver is ready to query.
 *
 * Data lives only for the page lifetime — sqljs writes to an
 * in-memory database. Persistence across reloads would need an IDB
 * sync layer on the worker side; out of scope for the initial
 * scaffold.
 */
actual class DatabaseDriverFactory {
    actual suspend fun create(): SqlDriver {
        val driver = createDefaultWebWorkerDriver()
        TagHistoryDatabase.Schema.awaitCreate(driver)
        return driver
    }
}
