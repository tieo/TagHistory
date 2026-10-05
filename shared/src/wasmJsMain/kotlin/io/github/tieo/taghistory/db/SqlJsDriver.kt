package io.github.tieo.taghistory.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Synchronous [SqlDriver] over an in-page sql.js database.
 *
 * The shared repositories call SQLDelight synchronously, as they do on
 * Android and the JVM. SQLDelight's web-worker driver is asynchronous and
 * cannot serve those calls, so the browser runs sql.js (SQLite compiled to
 * wasm, itself synchronous) on the page and this driver calls it directly.
 *
 * [onWrite] runs after every statement or transaction that may have changed
 * data; the factory uses it to schedule persisting the file to IndexedDB.
 */
class SqlJsDriver(
    private val db: JsAny,
    private val onWrite: () -> Unit,
) : SqlDriver {

    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    private var transaction: Transaction? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val statement = prepare(db, sql)
        try {
            bind(statement, parameters, binders)
            return mapper(Cursor(statement))
        } finally {
            freeStatement(statement)
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val statement = prepare(db, sql)
        try {
            bind(statement, parameters, binders)
            stepStatement(statement)
        } finally {
            freeStatement(statement)
        }
        if (transaction == null) onWrite()
        return QueryResult.Value(rowsModified(db).toLong())
    }

    private fun bind(statement: JsAny, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?) {
        if (binders == null || parameters == 0) return
        val values = Binder(parameters).apply(binders).values
        bindStatement(statement, values)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val enclosing = transaction
        if (enclosing == null) runSql(db, "BEGIN TRANSACTION")
        return QueryResult.Value(Transaction(enclosing).also { transaction = it })
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    private inner class Transaction(override val enclosingTransaction: Transaction?) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            if (enclosingTransaction == null) {
                runSql(db, if (successful) "COMMIT TRANSACTION" else "ROLLBACK TRANSACTION")
                onWrite()
            }
            transaction = enclosingTransaction
            return QueryResult.Unit
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        queryKeys.flatMap { listeners[it].orEmpty() }.toSet().forEach { it.queryResultsChanged() }
    }

    override fun close() = closeDatabase(db)

    /** Exports the whole database file. */
    fun export(): ByteArray = exportDatabase(db).toByteArray()

    private class Cursor(private val statement: JsAny) : SqlCursor {
        private var row: JsArray<JsAny?>? = null

        override fun next(): QueryResult<Boolean> {
            val hasRow = stepStatement(statement)
            row = if (hasRow) currentRow(statement) else null
            return QueryResult.Value(hasRow)
        }

        private fun value(index: Int): JsAny? = row!![index]

        override fun getString(index: Int): String? = value(index)?.let { (it as JsString).toString() }
        override fun getLong(index: Int): Long? = value(index)?.let { (it as JsNumber).toDouble().toLong() }
        override fun getDouble(index: Int): Double? = value(index)?.let { (it as JsNumber).toDouble() }
        override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }
        override fun getBytes(index: Int): ByteArray? = value(index)?.let { (it as Uint8Array).toByteArray() }
    }

    private class Binder(size: Int) : SqlPreparedStatement {
        val values: JsArray<JsAny?> = newArray(size)

        private fun set(index: Int, value: JsAny?) {
            values[index] = value
        }

        override fun bindBytes(index: Int, bytes: ByteArray?) = set(index, bytes?.toUint8Array())
        override fun bindLong(index: Int, long: Long?) = set(index, long?.toDouble()?.toJsNumber())
        override fun bindDouble(index: Int, double: Double?) = set(index, double?.toJsNumber())
        override fun bindString(index: Int, string: String?) = set(index, string?.toJsString())
        override fun bindBoolean(index: Int, boolean: Boolean?) =
            set(index, boolean?.let { if (it) 1.0 else 0.0 }?.toJsNumber())
    }
}

internal fun Uint8Array.toByteArray(): ByteArray {
    val view = Int8Array(buffer, byteOffset, length)
    return ByteArray(length) { view[it] }
}

internal fun ByteArray.toUint8Array(): Uint8Array {
    val out = Int8Array(size)
    forEachIndexed { i, b -> out[i] = b }
    return Uint8Array(out.buffer)
}

private fun prepare(db: JsAny, sql: String): JsAny = js("db.prepare(sql)")
private fun bindStatement(statement: JsAny, values: JsArray<JsAny?>): Unit = js("{ statement.bind(values); }")
private fun stepStatement(statement: JsAny): Boolean = js("statement.step()")
private fun currentRow(statement: JsAny): JsArray<JsAny?> = js("statement.get()")
private fun freeStatement(statement: JsAny): Unit = js("{ statement.free(); }")
private fun runSql(db: JsAny, sql: String): Unit = js("{ db.run(sql); }")
private fun rowsModified(db: JsAny): Int = js("db.getRowsModified()")
private fun closeDatabase(db: JsAny): Unit = js("{ db.close(); }")
private fun exportDatabase(db: JsAny): Uint8Array = js("db.export()")
private fun newArray(size: Int): JsArray<JsAny?> = js("new Array(size).fill(null)")
