package io.github.tieo.taghistory.data.repo

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.data.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Port of the RxDataStore-based Java `UserSettingsRepository`. Synchronous
 * read/write API on top of multiplatform-settings — the original was
 * pseudo-sync anyway (`blockingFirst()` / fire-and-forget `updateDataAsync`).
 *
 * Also exposes [flow] as a reactive view. The shell observes it to pick
 * dark/light theming without every consumer having to re-read on each
 * recomposition. Writes through this repo update both the underlying
 * Settings and the flow.
 */
class UserSettingsRepository(private val settings: Settings) {

    private val _flow = MutableStateFlow(read())

    /** Reactive view — emits on every [storeUserSettings] call. */
    val flow: StateFlow<UserSettings> = _flow.asStateFlow()

    fun getUserSettings(): UserSettings = _flow.value

    fun storeUserSettings(userSettings: UserSettings) {
        userSettings.language?.let { settings.putString(KEY_LANGUAGE, it) }
        userSettings.useDarkTheme?.let { settings.putBoolean(KEY_USE_DARK_THEME, it) }
        userSettings.enableDebugData?.let { settings.putBoolean(KEY_ENABLE_DEBUG_DATA, it) }
        userSettings.backgroundSyncEnabled?.let {
            settings.putBoolean(KEY_BACKGROUND_SYNC_ENABLED, it)
        }
        userSettings.backgroundSyncIntervalMinutes?.let {
            settings.putInt(KEY_BACKGROUND_SYNC_INTERVAL_MINUTES, it)
        }
        _flow.value = read()
    }

    /**
     * Clears the dark-theme override so the app follows the system
     * setting again. [storeUserSettings] can't express this because it
     * skips null-valued fields to avoid clobbering other keys.
     */
    fun clearDarkThemeOverride() {
        settings.remove(KEY_USE_DARK_THEME)
        _flow.value = read()
    }

    private fun read(): UserSettings = UserSettings(
        useDarkTheme = settings.getBooleanOrNull(KEY_USE_DARK_THEME),
        language = settings.getStringOrNull(KEY_LANGUAGE),
        enableDebugData = settings.getBooleanOrNull(KEY_ENABLE_DEBUG_DATA),
        // Background sync is the core feature — default ON. The user can still opt out.
        backgroundSyncEnabled = settings.getBooleanOrNull(KEY_BACKGROUND_SYNC_ENABLED) ?: true,
        backgroundSyncIntervalMinutes = settings.getIntOrNull(KEY_BACKGROUND_SYNC_INTERVAL_MINUTES)
            ?: DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES,
    )

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_USE_DARK_THEME = "use_dark_theme"
        const val KEY_ENABLE_DEBUG_DATA = "enable_debug_data"
        const val KEY_BACKGROUND_SYNC_ENABLED = "background_sync_enabled"
        const val KEY_BACKGROUND_SYNC_INTERVAL_MINUTES = "background_sync_interval_minutes"
        const val DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES = 60
    }
}
