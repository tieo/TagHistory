package io.github.tieo.taghistory.ui.map

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Compose pieces every platform's PlatformMapView shares: the chip markers
// and north button drawn over the native map. Styles, camera timing and the
// accuracy geometry live in :shared's MapDefaults.kt.


// Pin visual tuning — scale/alpha/rotation targets feed animateFloatAsState.
private const val SCALE_SELECTED = 1.15f
private const val SCALE_UNSELECTED = 0.75f
private const val ALPHA_SELECTED = 0.98f
private const val ALPHA_UNSELECTED = 0.55f
private const val ROTATION_UNSELECTED = -6f
private const val PIN_ANIM_MS = 280


/**
 * Unified pill marker — emoji + name in one rounded chip with a small
 * downward tail. Replaces the old Material `LocationOn` icon + separate
 * label-box combo (disjointed, over-weight). Scale + alpha animate on
 * selection via `animateFloatAsState`; chip width adapts to text length
 * via Row's intrinsic sizing. Tail tip anchors exactly at lat/lon.
 */
@Composable
internal fun ChipMarker(
    marker: BeaconMarkerUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    screenX: Float,
    screenY: Float,
) {
    val anim = tween<Float>(durationMillis = PIN_ANIM_MS, easing = FastOutSlowInEasing)
    val scale by animateFloatAsState(
        targetValue = if (isSelected) SCALE_SELECTED else SCALE_UNSELECTED,
        animationSpec = anim,
        label = "chipScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isSelected) ALPHA_SELECTED else ALPHA_UNSELECTED,
        animationSpec = anim,
        label = "chipAlpha",
    )

    // Same red for every chip — selected is brighter, unselected deeper
    // so the accent reads as a single brand colour, not two ad-hoc tints.
    val pillColor = if (isSelected) Color(0xFFE53935) else Color(0xFF8B2A2A)
    val contentColor = Color.White

    val tailHeight = 7.dp

    // Position chip via `Modifier.layout` — places post-measure so tail
    // tip lands exactly at (screenX, screenY) with no first-frame jitter
    // or left-shift (previous `onGloballyPositioned + offset` approach
    // flashed the chip at (0,y) until width was read back from layout).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(
                        x = (screenX - placeable.width / 2f).toInt(),
                        y = (screenY - placeable.height.toFloat()).toInt(),
                    )
                }
            }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            },
    ) {
        Row(
            modifier = Modifier
                .shadow(6.dp, RoundedCornerShape(50))
                .background(pillColor, RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val emoji = marker.emoji?.takeIf { it.isNotBlank() }
            if (emoji != null) {
                Text(text = emoji, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = marker.displayName,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Canvas(modifier = Modifier.size(12.dp, tailHeight)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path, pillColor)
        }
    }
}

/**
 * Compass FAB — fades in when bearing != 0, fades out at 0. Needle
 * animates to match bearing so user sees tilt. Tap → reset camera.
 * All transitions tweened for smooth appearance / disappearance.
 */
@Composable
internal fun NorthLockButton(
    bearing: Float,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = kotlin.math.abs(bearing) > 0.5f
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "compassAlpha",
    )
    val needleAngle by animateFloatAsState(
        targetValue = -bearing,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "compassAngle",
    )
    if (alpha <= 0.01f) return
    FilledIconButton(
        onClick = onReset,
        modifier = modifier.alpha(alpha),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.Navigation,
            contentDescription = "Reset to north",
            modifier = Modifier.graphicsLayer { rotationZ = needleAngle },
        )
    }
}
