package io.github.tieo.taghistory.data.repo

import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.model.UserSettings
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserSettingsRepositoryTest {

    private lateinit var props: Properties
    private lateinit var repo: UserSettingsRepository

    @BeforeTest
    fun setUp() {
        props = Properties()
        repo = UserSettingsRepository(PropertiesSettings(props))
    }

    @Test
    fun `empty store returns default-on background sync with theme unset`() {
        val s = repo.getUserSettings()
        assertNull(s.useDarkTheme)
        assertNull(s.language)
        assertNull(s.enableDebugData)
        // Background sync is the core feature — default ON with a 60-min interval.
        assertEquals(true, s.backgroundSyncEnabled)
        assertEquals(60, s.backgroundSyncIntervalMinutes)
        assertEquals(false, s.hasDarkThemeEnabled())
        assertEquals(true, s.isBackgroundSyncEnabled())
    }

    @Test
    fun `round-trips all fields`() {
        repo.storeUserSettings(
            UserSettings(
                useDarkTheme = true,
                language = "de",
                enableDebugData = false,
                backgroundSyncEnabled = true,
                backgroundSyncIntervalMinutes = 30,
            ),
        )

        val s = repo.getUserSettings()
        assertEquals(true, s.useDarkTheme)
        assertEquals("de", s.language)
        assertEquals(false, s.enableDebugData)
        assertEquals(true, s.backgroundSyncEnabled)
        assertEquals(30, s.backgroundSyncIntervalMinutes)
    }

    @Test
    fun `null fields do not clobber existing values`() {
        repo.storeUserSettings(
            UserSettings(
                useDarkTheme = true,
                language = "en",
                backgroundSyncIntervalMinutes = 15,
            ),
        )
        // Partial update: leaves language + interval alone.
        repo.storeUserSettings(UserSettings(useDarkTheme = false))

        val s = repo.getUserSettings()
        assertEquals(false, s.useDarkTheme)
        assertEquals("en", s.language)
        assertEquals(15, s.backgroundSyncIntervalMinutes)
    }

    @Test
    fun `flow emits on every write and reflects current value`() {
        // Initial subscription gets the empty state.
        assertNull(repo.flow.value.useDarkTheme)

        repo.storeUserSettings(UserSettings(useDarkTheme = true))
        assertEquals(true, repo.flow.value.useDarkTheme)

        repo.storeUserSettings(UserSettings(useDarkTheme = false))
        assertEquals(false, repo.flow.value.useDarkTheme)
    }

    @Test
    fun `clearDarkThemeOverride removes the key so flow reports null`() {
        repo.storeUserSettings(UserSettings(useDarkTheme = true))
        assertEquals(true, repo.flow.value.useDarkTheme)

        repo.clearDarkThemeOverride()

        // null means "follow system" — the shell resolves that to
        // isSystemInDarkTheme(). Verify the key was actually removed.
        assertNull(repo.flow.value.useDarkTheme)
        assertNull(repo.getUserSettings().useDarkTheme)
    }
}
