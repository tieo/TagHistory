package io.github.tieo.taghistory.data.model

import kotlinx.serialization.Serializable

/**
 * Last-known Google-Maps camera position. Persisted as JSON in the user
 * cache DataStore so we can restore the view on app restart.
 */
@Serializable
data class UserMapCameraPosition(
    val zoom: Float = 0f,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)
