package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import io.github.tieo.taghistory.mapSurfacesShown

/**
 * The Compose side of a [WebMap]: a box that keeps the map element under
 * itself, clears its part of the canvas, and passes on the pointer input
 * that reaches it. While it is composed the root surface is transparent (see
 * rootSurfaceColor), so nothing below it paints over the cleared area. Anything composed after it in the
 * same parent (markers, buttons, sheets) draws over the map and takes its
 * own input first.
 */
@Composable
internal fun MapSurface(
    map: WebMap?,
    modifier: Modifier,
    /** A click that did not drag, in CSS pixels from the map's top left. */
    onTap: ((x: Double, y: Double) -> Unit)? = null,
) {
    val density = LocalDensity.current.density.toDouble()
    DisposableEffect(Unit) {
        mapSurfacesShown.intValue++
        onDispose { mapSurfacesShown.intValue-- }
    }
    // Kept so a map created after the first layout still gets its box.
    var bounds by remember { mutableStateOf<List<Double>?>(null) }
    LaunchedEffect(map, bounds) {
        val b = bounds ?: return@LaunchedEffect
        map?.setBounds(b[0], b[1], b[2], b[3])
    }
    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                bounds = listOf(
                    pos.x / density,
                    pos.y / density,
                    coords.size.width / density,
                    coords.size.height / density,
                )
            }
            // Compose clears its canvas to opaque white before drawing; clear
            // this box back to transparent so the map behind shows.
            .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
            .pointerInput(map, onTap) {
                val m = map ?: return@pointerInput
                var lastPressMs = 0L
                var pressed = false
                var pressX = 0.0
                var pressY = 0.0
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val x = change.position.x / density
                        val y = change.position.y / density
                        when (event.type) {
                            PointerEventType.Press -> {
                                pressed = true
                                pressX = x
                                pressY = y
                                m.dispatch("mousedown", x, y, buttons = 1)
                                val now = change.uptimeMillis
                                if (now - lastPressMs < DOUBLE_CLICK_MS) m.dispatch("dblclick", x, y, buttons = 0)
                                lastPressMs = now
                            }
                            PointerEventType.Move -> m.dispatch("mousemove", x, y, buttons = if (pressed) 1 else 0)
                            PointerEventType.Release -> {
                                pressed = false
                                m.dispatch("mouseup", x, y, buttons = 0)
                                val moved = kotlin.math.hypot(x - pressX, y - pressY)
                                if (moved < TAP_SLOP_PX) onTap?.invoke(x, y)
                            }
                            // Compose reports wheel notches; browsers report
                            // about 100 pixels per notch, which MapLibre's
                            // wheel zoom is tuned for.
                            PointerEventType.Scroll -> m.dispatch(
                                "wheel", x, y, buttons = 0,
                                deltaX = change.scrollDelta.x * WHEEL_PX_PER_NOTCH,
                                deltaY = change.scrollDelta.y * WHEEL_PX_PER_NOTCH,
                            )
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    )
}

private const val DOUBLE_CLICK_MS = 300L
private const val TAP_SLOP_PX = 6.0
private const val WHEEL_PX_PER_NOTCH = 100.0
