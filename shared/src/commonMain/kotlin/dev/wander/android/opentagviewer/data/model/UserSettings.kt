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

    /**
     * Background sync is ON unless the user explicitly turned it off.
     * Null means "never touched the toggle" — and the app's core value
     * (location history) needs periodic background fetches, so unset
     * defaults to enabled rather than silently collecting nothing.
     */
    fun isBackgroundSyncEnabled(): Boolean = backgroundSyncEnabled != false

    /** The chosen sync interval, or the default when none was ever picked. */
    fun effectiveBackgroundSyncIntervalMinutes(): Int =
        backgroundSyncIntervalMinutes ?: DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES

    companion object {
        /**
         * The one default for the background sync interval. The settings
         * screen, the stored settings, the WorkManager job and the sync
         * throttle all read it from here so what the slider shows is what
         * gets scheduled.
         */
        const val DEFAULT_BACKGROUND_SYNC_INTERVAL_MINUTES: Int = 60
    }
}
