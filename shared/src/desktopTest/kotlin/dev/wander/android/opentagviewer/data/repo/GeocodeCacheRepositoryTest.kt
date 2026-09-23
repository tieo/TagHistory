package io.github.tieo.taghistory.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.db.TagHistoryDatabase
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeocodeCacheRepositoryTest {

    private val day = 24L * 60 * 60 * 1000
    private var now = 1_000L * day

    private fun repo(): Pair<GeocodeCacheRepository, TagHistoryDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(driver)
        val db = TagHistoryDatabase(driver)
        return GeocodeCacheRepository(db) { now } to db
    }

    @Test
    fun `eviction drops rows unused for longer than the window and keeps rows a read refreshed`() {
        val (cache, db) = repo()
        cache.put(1.0, 1.0, "Old unused")
        cache.put(2.0, 2.0, "Old but read")
        now += 200 * day
        assertEquals("Old but read", cache.get(2.0, 2.0)) // a hit refreshes last_used
        cache.put(3.0, 3.0, "Fresh")

        cache.evictOlderThan(180 * day)

        assertNull(cache.get(1.0, 1.0))
        assertEquals("Old but read", cache.get(2.0, 2.0))
        assertEquals("Fresh", cache.get(3.0, 3.0))
        assertEquals(2L, db.geocodeCacheQueries.countAll().executeAsOne())
    }
}
