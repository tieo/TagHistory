package io.github.tieo.taghistory.data.storage

import com.russhwolf.settings.Settings
import com.russhwolf.settings.StorageSettings

actual class SettingsFactory {
    actual fun create(name: String): Settings = StorageSettings()
}
