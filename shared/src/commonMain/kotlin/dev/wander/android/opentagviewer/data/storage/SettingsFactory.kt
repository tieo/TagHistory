package io.github.tieo.taghistory.data.storage

import com.russhwolf.settings.Settings

/**
 * Named preference stores. Matches the three separate DataStore files the
 * Java app used (`user_settings`, `user_auth`, `user_cache`) — the repo
 * layer asks for a name, this knows how to hand back a `Settings` for
 * that name on whichever platform we're running.
 */
expect class SettingsFactory {
    fun create(name: String): Settings
}

/** Canonical store names, mirrored from the Java DataStore filenames. */
object SettingsStoreNames {
    const val USER_SETTINGS = "user_settings"
    const val USER_AUTH = "user_auth"
    const val USER_CACHE = "user_cache"
}
