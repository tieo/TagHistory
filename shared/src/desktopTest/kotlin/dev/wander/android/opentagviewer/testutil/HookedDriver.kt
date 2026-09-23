package io.github.tieo.taghistory.testutil

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * SqlDriver that forwards everything to [delegate] and runs [afterQuery] after
 * a query has returned its rows. Lets a test inject a write, or park the
 * reading thread, at an exact point between "rows read" and "result used",
 * which is how the cache and render races in this codebase are reproduced
 * deterministically instead of by chance.
 */
class HookedDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    @Volatile
    var afterQuery: ((String) -> Unit)? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        afterQuery?.invoke(sql)
        return result
    }
}
