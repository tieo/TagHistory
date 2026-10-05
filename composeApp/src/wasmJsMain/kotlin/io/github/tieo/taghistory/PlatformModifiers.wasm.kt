package io.github.tieo.taghistory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.browser.document

actual fun Modifier.withTestTagsAsResourceId(): Modifier = this

/**
 * Map surfaces currently composed. Each draws its map in an element behind
 * the canvas, so while one is on screen the root must not paint over it.
 */
internal val mapSurfacesShown = mutableIntStateOf(0)

@Composable
actual fun rootSurfaceColor(themeColor: Color): Color {
    SideEffect {
        val argb = themeColor.toArgb()
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
        // On <html>, not <body>: the map host has a negative z-index and is
        // painted above the root element's background but below <body>'s.
        (document.documentElement as? org.w3c.dom.HTMLElement)?.style?.background = "#$rgb"
    }
    return if (mapSurfacesShown.intValue > 0) Color.Transparent else themeColor
}
