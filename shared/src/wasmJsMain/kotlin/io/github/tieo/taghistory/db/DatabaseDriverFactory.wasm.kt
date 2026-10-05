package io.github.tieo.taghistory.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

@JsModule("sql.js")
private external fun initSqlJs(config: JsAny): Promise<JsAny>

/**
 * wasmJs DatabaseDriverFactory: sql.js on the page through [SqlJsDriver],
 * with the database file kept in IndexedDB so the replicated history
 * survives reloads.
 *
 * The file is written back after changes, debounced, and again when the
 * page is hidden, which is the last moment a browser reliably runs code
 * before a tab is closed. The schema version lives in `PRAGMA
 * user_version`, as on the other platforms, so a file saved by an older
 * build is migrated rather than re-created.
 */
actual class DatabaseDriverFactory(
    /** IndexedDB database holding the file; tests pass their own. */
    private val storeName: String = "taghistory-db",
) {
    actual suspend fun create(): SqlDriver {
        val sql = initSqlJs(sqlJsConfig()).await<JsAny>()
        val saved = loadFile(storeName).await<JsAny?>() as Uint8Array?
        val db = newDatabase(sql, saved)

        var timer: Int? = null
        lateinit var driver: SqlJsDriver
        fun flush() {
            timer?.let { window.clearTimeout(it) }
            timer = null
            saveFile(storeName, exportFile(db))
        }
        driver = SqlJsDriver(db) {
            timer?.let { window.clearTimeout(it) }
            timer = window.setTimeout({ flush(); null }, WRITE_DEBOUNCE_MS)
        }
        onPageHidden { if (timer != null) flush() }

        migrate(driver)
        flush()
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

    private companion object {
        const val WRITE_DEBOUNCE_MS = 500
    }
}

private fun sqlJsConfig(): JsAny = js("({ locateFile: () => '/sql-wasm.wasm' })")

private fun newDatabase(sql: JsAny, file: Uint8Array?): JsAny =
    js("file ? new sql.Database(file) : new sql.Database()")

private fun exportFile(db: JsAny): Uint8Array = js("db.export()")

private fun onPageHidden(block: () -> Unit): Unit =
    js("{ document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') block(); }); }")

private fun loadFile(store: String): Promise<JsAny?> = js(
    """new Promise((resolve) => {
        const open = indexedDB.open(store, 1);
        open.onupgradeneeded = () => open.result.createObjectStore('blobs');
        open.onerror = () => resolve(null);
        open.onsuccess = () => {
            const get = open.result.transaction('blobs', 'readonly').objectStore('blobs').get('sqlite');
            get.onsuccess = () => resolve(get.result || null);
            get.onerror = () => resolve(null);
        };
    })""",
)

private fun saveFile(store: String, data: Uint8Array): Unit = js(
    """{
        const open = indexedDB.open(store, 1);
        open.onupgradeneeded = () => open.result.createObjectStore('blobs');
        open.onsuccess = () => {
            open.result.transaction('blobs', 'readwrite').objectStore('blobs').put(data, 'sqlite');
        };
        open.onerror = () => console.warn('TagHistory: saving the database failed', open.error);
    }""",
)
