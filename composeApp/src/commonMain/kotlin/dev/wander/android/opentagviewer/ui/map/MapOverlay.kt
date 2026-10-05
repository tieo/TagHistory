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
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Map pieces every platform's PlatformMapView shares: the chip markers and
// north button drawn in Compose over the native map, camera timing, the
// accuracy-circle geometry and the basemap styles.

/** Default zoom when focusing a tag: street level. */
internal const val DEFAULT_FOCUS_ZOOM = 16.0

/** A saved camera below this zoom (world view) is not worth restoring. */
internal const val MEANINGFUL_ZOOM_FLOOR = 6.0

// Pin visual tuning — scale/alpha/rotation targets feed animateFloatAsState.
private const val SCALE_SELECTED = 1.15f
private const val SCALE_UNSELECTED = 0.75f
private const val ALPHA_SELECTED = 0.98f
private const val ALPHA_UNSELECTED = 0.55f
private const val ROTATION_UNSELECTED = -6f
private const val PIN_ANIM_MS = 280

private const val CAMERA_MIN_MS = 300
private const val CAMERA_MAX_MS = 1200
private const val CAMERA_LERP_KM = 100.0

internal fun cameraDurationFor(distMeters: Double): Int {
    val km = distMeters / 1000.0
    val frac = (km / CAMERA_LERP_KM).coerceIn(0.0, 1.0)
    return (CAMERA_MIN_MS + frac * (CAMERA_MAX_MS - CAMERA_MIN_MS)).toInt()
}

internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2).let { it * it } +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
        sin(dLon / 2).let { it * it }
    val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return r * c
}

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


/**
 * CartoDB Voyager: vector OSM style with POI labels, landmarks, parks and
 * transit, so a tag can be placed near the shop or street it is at. Free, no
 * API key, attribution to CARTO and OSM.
 */
internal const val LIGHT_STYLE_URL = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"

/** CartoDB Dark Matter, the vector variant for crisp labels. */
internal const val DARK_STYLE_URL = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

/**
 * Esri World Imagery raster tiles, free without an API key at reasonable
 * volume, wrapped in a minimal style so it loads like every other basemap.
 */
internal val SATELLITE_STYLE_JSON = """
{
  "version": 8,
  "name": "Esri World Imagery",
  "sources": {
    "esri-imagery": {
      "type": "raster",
      "tiles": [
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
      ],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© Esri, Maxar, Earthstar Geographics, GIS User Community"
    }
  },
  "layers": [
    {"id": "bg", "type": "background", "paint": {"background-color": "#000000"}},
    {"id": "base", "type": "raster", "source": "esri-imagery"}
  ]
}
""".trimIndent()

/** Accuracy circle colours, shared so both maps tint them alike. */
internal const val ACCURACY_COLOR = "#8B2A2A"

/** WGS-84 circle approximation as a closed ring of (longitude, latitude). */
internal fun circleRing(
    centerLat: Double,
    centerLon: Double,
    radiusM: Double,
    segments: Int,
): List<Pair<Double, Double>> {
    val earthR = 6_371_000.0
    val lat0 = centerLat * PI / 180.0
    val lon0 = centerLon * PI / 180.0
    val angularDist = radiusM / earthR
    val out = ArrayList<Pair<Double, Double>>(segments + 1)
    for (i in 0..segments) {
        val bearing = 2.0 * PI * i / segments
        val newLat = asin(
            sin(lat0) * cos(angularDist) +
                cos(lat0) * sin(angularDist) * cos(bearing),
        )
        val newLon = lon0 + atan2(
            sin(bearing) * sin(angularDist) * cos(lat0),
            cos(angularDist) - sin(lat0) * sin(newLat),
        )
        out += (newLon * 180.0 / PI) to (newLat * 180.0 / PI)
    }
    return out
}
