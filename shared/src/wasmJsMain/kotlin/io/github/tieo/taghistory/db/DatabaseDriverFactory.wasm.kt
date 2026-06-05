package io.github.tieo.taghistory.db

import app.cash.sqldelight.db.SqlDriver

private fun NI(): Nothing = throw NotImplementedError(
    "DatabaseDriverFactory wasmJs actual needs the SQLDelight web-worker driver " +
        "(app.cash.sqldelight:web-worker-driver) + a JS-side sqljs loader. Not wired yet.",
)

actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = NI()
}
