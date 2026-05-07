package io.github.tieo.taghistory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.taghistory.data.model.UserSettings
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * KMP ViewModel for the user settings screen. Settings are read through
 * [UserSettingsRepository] (multiplatform-settings backing it) — the
 * viewmodel just synchronises a flow with the repo for Compose to render.
 *
 * Sign-out hits [UserAuthRepository.clearUser] and emits an event flag
 * the host watches, so it can flip back to login without coupling the
 * viewmodel to navigation.
 */
class SettingsViewModel(
    private val settingsRepo: UserSettingsRepository,
    private val authRepo: UserAuthRepository,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val runScope: CoroutineScope get() = scope ?: viewModelScope

    fun load() {
        _state.value = SettingsUiState(current = settingsRepo.getUserSettings())
    }

    fun setDarkTheme(enabled: Boolean) = update { it.copy(useDarkTheme = enabled) }
    fun setBackgroundSyncEnabled(enabled: Boolean) =
        update { it.copy(backgroundSyncEnabled = enabled) }
    fun setBackgroundSyncIntervalMinutes(minutes: Int) =
        update { it.copy(backgroundSyncIntervalMinutes = minutes) }
    fun setEnableDebugData(enabled: Boolean) = update { it.copy(enableDebugData = enabled) }

    /**
     * Tri-state theme selector. Null means "follow system"; the
     * repository has to go through [UserSettingsRepository.clearDarkThemeOverride]
     * rather than [update] because [storeUserSettings] skips null-valued
     * fields to avoid clobbering other keys.
     */
    fun setThemeMode(useDark: Boolean?) {
        if (useDark == null) {
            settingsRepo.clearDarkThemeOverride()
        } else {
            settingsRepo.storeUserSettings(
                _state.value.current.copy(useDarkTheme = useDark),
            )
        }
        _state.update { it.copy(current = settingsRepo.getUserSettings()) }
    }

    fun signOut() {
        runScope.launch {
            authRepo.clearUser()
            _state.update { it.copy(signedOut = true) }
        }
    }

    private fun update(transform: (UserSettings) -> UserSettings) {
        val next = transform(_state.value.current)
        settingsRepo.storeUserSettings(next)
        _state.update { it.copy(current = next) }
    }
}

data class SettingsUiState(
    val current: UserSettings = UserSettings(),
    val signedOut: Boolean = false,
)
