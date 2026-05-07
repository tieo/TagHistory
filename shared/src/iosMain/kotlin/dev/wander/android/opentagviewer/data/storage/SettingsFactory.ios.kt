package io.github.tieo.taghistory.data.storage

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import platform.Foundation.NSUserDefaults

/**
 * iOS uses NSUserDefaults with a suite name equal to the store name so
 * each logical store has its own key namespace.
 */
actual class SettingsFactory {
    actual fun create(name: String): Settings =
        NSUserDefaultsSettings(NSUserDefaults(suiteName = name))
}
