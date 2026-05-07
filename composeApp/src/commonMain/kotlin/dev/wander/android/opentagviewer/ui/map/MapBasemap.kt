package io.github.tieo.taghistory.ui.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.luminance

/** Three basemap modes the user can cycle via the top-right FAB. */
enum class MapBasemap { LIGHT, DARK, SATELLITE }

/**
 * Default basemap from active theme. Tied to the tri-state theme setting
 * so flipping theme in Settings flips the map too; the user can still
 * override per-session via the in-map cycle button.
 */
@Composable
@ReadOnlyComposable
fun defaultBasemap(): MapBasemap =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) MapBasemap.DARK
    else MapBasemap.LIGHT
