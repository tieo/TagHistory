package io.github.tieo.taghistory.data.storage

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Wraps Android SharedPreferences as `Settings`. We use MODE_PRIVATE to
 * keep each named store per-app, matching DataStore's isolation.
 */
actual class SettingsFactory(private val context: Context) {
    actual fun create(name: String): Settings {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return SharedPreferencesSettings(prefs)
    }
}
