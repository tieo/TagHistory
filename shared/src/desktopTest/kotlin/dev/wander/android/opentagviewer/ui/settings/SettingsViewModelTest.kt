package io.github.tieo.taghistory.ui.settings

import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var settingsRepo: UserSettingsRepository
    private lateinit var authRepo: UserAuthRepository

    @BeforeTest
    fun setUp() {
        settingsRepo = UserSettingsRepository(PropertiesSettings(Properties()))
        authRepo = UserAuthRepository(
            settings = PropertiesSettings(Properties()),
            crypto = SecureBlobStore(),
            keystoreAlias = "test",
        )
        authRepo.storeUserAuth("""{"account":null}""".encodeToByteArray())
    }

    @Test
    fun `load emits current settings`() = runTest {
        val vm = SettingsViewModel(settingsRepo, authRepo, scope = this)
        vm.load()
        val s = vm.state.value.current
        // Nothing persisted — theme still null, but background sync defaults ON.
        assertNull(s.useDarkTheme)
        assertEquals(true, s.backgroundSyncEnabled)
    }

    @Test
    fun `setters persist through the repo`() = runTest {
        val vm = SettingsViewModel(settingsRepo, authRepo, scope = this)
        vm.load()
        vm.setDarkTheme(true)
        vm.setBackgroundSyncEnabled(true)
        vm.setBackgroundSyncIntervalMinutes(45)
        vm.setEnableDebugData(false)
        assertEquals(true, vm.state.value.current.useDarkTheme)
        assertEquals(45, vm.state.value.current.backgroundSyncIntervalMinutes)
        // Fresh load from repo should see the same values.
        val fresh = settingsRepo.getUserSettings()
        assertEquals(true, fresh.useDarkTheme)
        assertEquals(45, fresh.backgroundSyncIntervalMinutes)
    }

    @Test
    fun `sign out clears auth and flips signedOut`() = runTest {
        val vm = SettingsViewModel(settingsRepo, authRepo, scope = this)
        assertFalse(vm.state.value.signedOut)
        vm.signOut()
        advanceUntilIdle()
        assertTrue(vm.state.value.signedOut)
        assertNull(authRepo.getUserAuth())
    }

    @Test
    fun `setThemeMode null clears the dark-theme override`() = runTest {
        val vm = SettingsViewModel(settingsRepo, authRepo, scope = this)
        vm.load()
        vm.setThemeMode(true)
        assertEquals(true, vm.state.value.current.useDarkTheme)

        vm.setThemeMode(null) // "follow system"
        assertNull(vm.state.value.current.useDarkTheme)
        assertNull(settingsRepo.getUserSettings().useDarkTheme)
    }

    @Test
    fun `setThemeMode true and false are persisted`() = runTest {
        val vm = SettingsViewModel(settingsRepo, authRepo, scope = this)
        vm.load()
        vm.setThemeMode(true)
        assertEquals(true, settingsRepo.getUserSettings().useDarkTheme)

        vm.setThemeMode(false)
        assertEquals(false, settingsRepo.getUserSettings().useDarkTheme)
    }
}
