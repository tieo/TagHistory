package io.github.tieo.taghistory.ui.util

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CircularProgressIndicator equivalent that keeps spinning even when the
 * device has animator_duration_scale = 0 (developer-options "animations
 * off") because it advances rotation directly from withFrameNanos rather
 * than via the InfiniteTransition / MotionDurationScale machinery.
 */
@Composable
fun AlwaysSpinningIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
    sweepDegrees: Float = 270f,
    revolutionsPerSecond: Float = 1f,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(revolutionsPerSecond) {
        var prev = 0L
        while (true) {
            withFrameNanos { now ->
                if (prev != 0L) {
                    val dt = (now - prev) / 1_000_000_000f
                    angle = (angle + dt * 360f * revolutionsPerSecond) % 360f
                }
                prev = now
            }
        }
    }
    Canvas(modifier = modifier) {
        val sw = strokeWidth.toPx()
        val pad = sw / 2f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = sweepDegrees,
            useCenter = false,
            topLeft = Offset(pad, pad),
            size = Size(size.width - 2 * pad, size.height - 2 * pad),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}
