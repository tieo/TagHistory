package io.github.tieo.taghistory.data.model

/**
 * Mirrors the Java `UserSettings` POJO. All fields nullable because the
 * underlying DataStore can have "never set" semantics (first-run users);
 * the two `hasXEnabled()` helpers collapse null→false.
 */
data class UserSettings(
    val useDarkTheme: Boolean? = null,
    val language: String? = null,
    val enableDebugData: Boolean? = null,
    val backgroundSyncEnabled: Boolean? = null,
    val backgroundSyncIntervalMinutes: Int? = null,
) {
    fun hasDarkThemeEnabled(): Boolean = useDarkTheme == true
    fun isBackgroundSyncEnabled(): Boolean = backgroundSyncEnabled == true
}
