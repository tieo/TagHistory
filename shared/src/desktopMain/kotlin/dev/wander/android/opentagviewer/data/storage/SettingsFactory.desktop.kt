package io.github.tieo.taghistory.data.storage

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

/**
 * Desktop uses java.util.prefs. The prefix namespace isolates TagHistory
 * stores from any other app sharing the JVM preferences root.
 */
actual class SettingsFactory {
    actual fun create(name: String): Settings {
        val node = Preferences.userRoot().node("io/github/tieo/taghistory/$name")
        return PreferencesSettings(node)
    }
}
