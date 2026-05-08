package io.github.tieo.taghistory.host

import android.content.Context
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.data.storage.SettingsFactory
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.TagHistoryDatabase

private const val SEED_BEACON_1 = "test-beacon-001-aaa"
private const val SEED_BEACON_2 = "test-beacon-002-bbb"
private const val SEED_BEACON_3 = "test-beacon-003-ccc"
private const val DAY_MS = 24L * 60L * 60L * 1_000L

/**
 * Seeds fake auth + beacons + location reports for Maestro E2E tests.
 * Idempotent — safe to call on every test run; skips if already seeded.
 * Only called from [TestSeedActivity] in the debug build; ships in release
 * but is never invoked there.
 */
fun seedTestData(context: Context) {
    val app = context.applicationContext
    val db = TagHistoryDatabase(DatabaseDriverFactory(app).create())

    if (db.ownedBeaconQueries.getById(SEED_BEACON_1).executeAsOneOrNull() != null) return

    // ── Auth ──────────────────────────────────────────────────────────────
    val settings = SettingsFactory(app)
    val crypto = SecureBlobStore()
    UserAuthRepository(
        settings.create("user_auth"),
        crypto,
        "apple_account_key",
    ).storeUserAuth(
        // Minimal valid AppleAccount JSON. login_state.state = 3 = LOGGED_IN.
        """{"ids":{"uid":"00000000-test-uid-0000","devid":"00000000-dev-0000"},"account":{"username":"test@taghistory.dev","password":"fake","info":{"account_name":"test@taghistory.dev","first_name":"Test","last_name":"User"}},"login_state":{"state":3,"data":{}}}"""
            .encodeToByteArray()
    )

    // ── Beacons ───────────────────────────────────────────────────────────
    val now = System.currentTimeMillis()
    db.importQueries.insert(
        version = "1",
        importedAt = now,
        exportedAt = now,
        sourceUser = "test@taghistory.dev",
        via = "test-seeder",
    )
    val importId = db.importQueries.lastInsertedId().executeAsOne()

    listOf(
        Triple(SEED_BEACON_1, "Car Keys", "🔑"),
        Triple(SEED_BEACON_2, "Backpack", "🎒"),
        Triple(SEED_BEACON_3, "Bike", "🚲"),
    ).forEach { (id, name, emoji) ->
        db.ownedBeaconQueries.upsert(
            id = id,
            importId = importId,
            content = "",   // non-null so BeaconInformationParser doesn't short-circuit
            version = "1",
            isRemoved = false,
        )
        db.userBeaconOptionsQueries.upsert(
            beaconId = id,
            lastUpdate = now,
            uiName = name,
            uiEmoji = emoji,
        )
    }

    // ── Location reports (Berlin centre, multi-day for history screen) ────
    // Use small now-relative offsets so the "today" bucket always contains
    // all 3 of beacon 1's today-points regardless of test runtime / TZ;
    // big offsets target yesterday and 3 days ago.
    BeaconRepository(db).storeToLocationCache(
        mapOf(
            SEED_BEACON_1 to listOf(
                fakeReport(now - 1 * 60_000L,                52.5200, 13.4050, 10L),
                fakeReport(now - 3 * 60_000L,                52.5210, 13.4060, 15L),
                fakeReport(now - 5 * 60_000L,                52.5220, 13.4070, 20L),
                fakeReport(now - DAY_MS - 60 * 60_000L,      52.5180, 13.4020, 25L),
                fakeReport(now - DAY_MS - 3 * 60 * 60_000L,  52.5170, 13.4010, 30L),
                fakeReport(now - 3 * DAY_MS,                 52.5150, 13.3990, 35L),
            ),
            SEED_BEACON_2 to listOf(
                fakeReport(now - 2 * 60_000L,                52.5110, 13.4150, 12L),
                fakeReport(now - DAY_MS - 30 * 60_000L,      52.5120, 13.4140, 18L),
            ),
            SEED_BEACON_3 to listOf(
                fakeReport(now - 4 * 60_000L,                52.5090, 13.3950, 8L),
            ),
        )
    )
}

private fun fakeReport(ts: Long, lat: Double, lon: Double, accuracy: Long) =
    BeaconLocationReport(
        publishedAt = ts,
        description = "seed",
        timestamp = ts,
        confidence = 2L,
        latitude = lat,
        longitude = lon,
        horizontalAccuracy = accuracy,
        status = 0L,
    )
