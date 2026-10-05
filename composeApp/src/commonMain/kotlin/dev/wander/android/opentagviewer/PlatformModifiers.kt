package io.github.tieo.taghistory

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

expect fun Modifier.withTestTagsAsResourceId(): Modifier

/**
 * Color for the app's root surface, given the theme's. The web draws its map
 * in a page element behind the Compose canvas, so there the root is
 * transparent while a map is on screen; everywhere else, and on the web's
 * other screens, the root paints the theme color.
 */
@Composable
expect fun rootSurfaceColor(themeColor: Color): Color
