package io.github.tieo.taghistory.db

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver

/**
 * Build a SqlDriver backed by `resources/idb-sqljs-worker.js`, which
 * mirrors the @cashapp/sqldelight-sqljs-worker message protocol but
 * additionally serialises the SQLite blob to IndexedDB on every
 * mutating statement (debounced 500 ms). Reloads of the page
 * rehydrate from IDB so cards + auth + history survive across
 * sessions.
 *
 * Webpack rewrites `new URL("./idb-sqljs-worker.js", import.meta.url)`
 * at bundle time into an emitted asset reference — the JS file
 * gets fingerprinted and copied into the dist alongside composeApp.js.
 */
suspend fun createIdbBackedDriver(): SqlDriver {
    val worker = jsCreateWorker()
    val driver = WebWorkerDriver(worker)
    TagHistoryDatabase.Schema.awaitCreate(driver)
    return driver
}

private fun jsCreateWorker(): org.w3c.dom.Worker =
    js("new Worker(new URL(\"./idb-sqljs-worker.js\", import.meta.url))")
