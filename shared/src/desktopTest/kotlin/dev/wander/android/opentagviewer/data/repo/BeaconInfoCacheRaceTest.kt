package io.github.tieo.taghistory.data.repo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.tieo.taghistory.data.model.ImportData
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.Import
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.UserBeaconOptions
import io.github.tieo.taghistory.testutil.HookedDriver
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The beacon-info cache is read from several threads while writes (rename,
 * import, remove) invalidate it. A reader that has already read the tables
 * when a write commits must not be able to leave its stale snapshot in the
 * cache. The driver below commits a rename right after the reader's
 * UserBeaconOptions query returns and before the reader publishes, which is
 * exactly that interleaving, reproduced deterministically.
 */
class BeaconInfoCacheRaceTest {

    @Test
    fun `a rename committing mid-read does not leave the stale name cached`() {
        val jdbc = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        TagHistoryDatabase.Schema.create(jdbc)
        val driver = HookedDriver(jdbc)
        val repo = BeaconRepository(TagHistoryDatabase(driver)) { 1L }

        repo.addNewImport(
            ImportData(
                anImport = Import(
                    id = 0L, version = "v1", imported_at = 1L, exported_at = 2L,
                    source_user = "me@example.com", via = "unit-test",
                ),
                ownedBeacons = listOf(
                    OwnedBeacons(id = "b1", import_id = 0L, content = "c", version = "v1", is_removed = false),
                ),
                beaconNamingRecords = listOf(
                    BeaconNamingRecord(id = "b1", import_id = 0L, version = "v1", content = "n", is_removed = false),
                ),
            ),
        )
        repo.storeUserBeaconOptions(UserBeaconOptions("b1", 1L, "Old", null))

        var fired = false
        driver.afterQuery = { sql ->
            if (!fired && "FROM UserBeaconOptions" in sql) {
                fired = true
                repo.storeUserBeaconOptions(UserBeaconOptions("b1", 2L, "New", null))
            }
        }
        val readerThatRaced = repo.getAllBeaconInformation()
        driver.afterQuery = null

        // The racing reader itself saw the pre-rename rows; that part is fine.
        assertEquals(true, fired)
        assertEquals("Old", readerThatRaced["b1"]?.userOverrideName)
        // What must not happen: its snapshot being served after the rename.
        assertEquals("New", repo.getAllBeaconInformation()["b1"]?.userOverrideName)
    }
}
