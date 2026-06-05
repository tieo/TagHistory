package io.github.tieo.taghistory.db

import app.cash.sqldelight.db.SqlDriver

/**
 * wasmJs DatabaseDriverFactory placeholder. SqlDelight's
 * `web-worker-driver` exposes only `suspend` constructors / open()
 * + `Schema.create` is also suspend on the async path, but the
 * existing `expect class DatabaseDriverFactory { fun create(): SqlDriver }`
 * commonMain signature is non-suspend. Bridging that without
 * touching commonMain needs a runBlocking shim, which Kotlin/Wasm
 * does not provide.
 *
 * Until commonMain is migrated to suspend (or a sync sqljs adapter
 * lands), DB-backed flows on web still throw — but with a clearer
 * message than the previous "not wired yet" stub.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = throw NotImplementedError(
        "Web DB driver not connected — SqlDelight's wasm path is async-only " +
            "and the commonMain DatabaseDriverFactory.create() is sync. " +
            "Bridging needs an expect-class migration to suspend."
    )
}
