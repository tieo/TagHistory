package io.github.tieo.taghistory.data.repo

import com.russhwolf.settings.Settings
import io.github.tieo.taghistory.data.model.UserMapCameraPosition
import kotlinx.serialization.json.Json

/**
 * UI-state cache (currently just last-known map camera). JSON-encoded
 * so schema evolution doesn't break old saves. `ignoreUnknownKeys` is on
 * for forward-compat with newer fields added after a release.
 */
class UserDataRepository(
    private val settings: Settings,
    private val json: Json = DefaultJson,
) {

    fun getLastCameraPosition(): UserMapCameraPosition? {
        val raw = settings.getStringOrNull(KEY_MAP_CAMERA_ORIENTATION) ?: return null
        return try {
            json.decodeFromString(UserMapCameraPosition.serializer(), raw)
        } catch (_: Exception) {
            // Corrupted / legacy payloads: treat as "nothing stored" rather
            // than poisoning startup. The user will just see default camera.
            null
        }
    }

    fun storeLastCameraPosition(position: UserMapCameraPosition): UserMapCameraPosition {
        val encoded = json.encodeToString(UserMapCameraPosition.serializer(), position)
        settings.putString(KEY_MAP_CAMERA_ORIENTATION, encoded)
        return position
    }

    private companion object {
        const val KEY_MAP_CAMERA_ORIENTATION = "map_camera_orientation"
        val DefaultJson = Json { ignoreUnknownKeys = true }
    }
}
