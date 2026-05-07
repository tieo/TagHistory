package io.github.tieo.taghistory.data.repo

import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserDataRepositoryTest {

    private lateinit var props: Properties
    private lateinit var repo: UserDataRepository

    @BeforeTest
    fun setUp() {
        props = Properties()
        repo = UserDataRepository(PropertiesSettings(props))
    }

    @Test
    fun `empty store returns null camera position`() {
        assertNull(repo.getLastCameraPosition())
    }

    @Test
    fun `round-trips camera position via JSON`() {
        val pos = UserMapCameraPosition(zoom = 12.5f, lat = 52.5200, lon = 13.4050)
        val stored = repo.storeLastCameraPosition(pos)
        assertEquals(pos, stored)
        assertEquals(pos, repo.getLastCameraPosition())
    }

    @Test
    fun `corrupted payload returns null instead of throwing`() {
        // Write garbage directly to simulate a schema break / torn write.
        props.setProperty("map_camera_orientation", "not-a-json-object")
        assertNull(repo.getLastCameraPosition())
    }
}
