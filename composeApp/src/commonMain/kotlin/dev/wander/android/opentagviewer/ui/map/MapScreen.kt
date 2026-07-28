package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Satellite
import io.github.tieo.taghistory.ui.util.AlwaysSpinningIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.launch

/**
 * Compose MP port of the legacy `MapsActivity`. Map doubles as device
 * list via bottom tag pager — swipe = select + pan map. Per-card actions:
 * Route (system nav-app chooser), History, Details.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onOpenDevice: (String) -> Unit = {},
    onOpenHistory: (String, String) -> Unit = { _, _ -> },
    onManageTags: () -> Unit = {},
    onRoute: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onImport: (suspend () -> String?)? = null,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Periodic refresh lives in MapViewModel.init so it keeps running
    // across every screen, not just while MapScreen is composed.

    val refreshError = state.refreshError
    LaunchedEffect(refreshError, snackbarHostState) {
        val host = snackbarHostState ?: return@LaunchedEffect
        val msg = refreshError ?: return@LaunchedEffect
        host.showSnackbar(msg.take(80), duration = SnackbarDuration.Short)
    }

    val themeDefault = defaultBasemap()
    var basemap by remember(themeDefault) { mutableStateOf(themeDefault) }
    val haptics = LocalHapticFeedback.current
    // Bottom inset = height of the glass tag list overlay so the map
    // camera centers above it instead of behind it. Matches the
    // TagGlassList height calculation (screen * 0.45f). Empty cards
    // case skips the list entirely so inset = 0.
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val listHeightDp = with(density) { (windowInfo.containerSize.height * 0.45f).toDp() }
    val bottomInsetPx = if (state.cards.isEmpty()) 0
    else with(density) { listHeightDp.toPx().toInt() }

    Box(modifier = modifier.fillMaxSize()) {
        PlatformMapView(
            markers = state.markers,
            selectedBeaconId = state.selectedBeaconId,
            initialCamera = state.initialCamera,
            basemap = basemap,
            onMarkerClick = viewModel::selectBeacon,
            onCameraIdle = viewModel::saveCamera,
            bottomInsetPx = bottomInsetPx,
            modifier = Modifier.fillMaxSize(),
        )

        // Cycle-basemap FAB, top-left. Light → Dark → Satellite → Light.
        BasemapCycleButton(
            current = basemap,
            onCycle = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                basemap = when (basemap) {
                    MapBasemap.LIGHT -> MapBasemap.DARK
                    MapBasemap.DARK -> MapBasemap.SATELLITE
                    MapBasemap.SATELLITE -> MapBasemap.LIGHT
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
        )

        when {
            state.cards.isEmpty() && (state.isRefreshing || !state.isInitialFetchComplete) -> {
                LoadingShimmerCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            state.cards.isEmpty() -> {
                EmptyDevicesCard(
                    onImport = onImport,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            state.cards.isNotEmpty() -> {
                TagGlassList(
                    cards = state.cards,
                    selectedBeaconId = state.selectedBeaconId,
                    fetchingBeaconIds = state.fetchingBeaconIds,
                    isRefreshing = state.isRefreshing,
                    hasError = state.refreshError != null,
                    onRefresh = viewModel::refresh,
                    onManageTags = onManageTags,
                    onSelect = viewModel::selectBeacon,
                    onOpenInfo = onOpenDevice,
                    onOpenHistory = onOpenHistory,
                    onOpenNearby = null,
                    onRoute = { beaconId ->
                        val card = state.cards.firstOrNull { it.beaconId == beaconId } ?: return@TagGlassList
                        val lat = card.latitude; val lon = card.longitude
                        if (lat != null && lon != null) onRoute(lat, lon, card.displayName)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard(
    onImport: (suspend () -> String?)?,
    snackbarHostState: SnackbarHostState?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "No AirTags yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Import your Find My export zip to start tracking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onImport != null) {
                FilledTonalButton(
                    onClick = {
                        if (importing) return@FilledTonalButton
                        importing = true
                        scope.launch {
                            val msg = try { onImport() } catch (e: Exception) { e.message }
                            importing = false
                            if (msg != null) {
                                snackbarHostState?.showSnackbar(msg.take(80))
                            }
                        }
                    },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().testTag("btn_import"),
                ) {
                    if (importing) {
                        AlwaysSpinningIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Import zip")
                    }
                }
            }
        }
    }
}

/**
 * Vertical glass-style list of every known tag. Replaces the
 * horizontal one-at-a-time pager so the user sees their whole fleet
 * without swiping. The container is a translucent rounded surface
 * anchored at the bottom (~45% of screen height); selecting a row
 * still drives the map's selected beacon, keeping the rest of the
 * MapScreen wiring unchanged.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun TagGlassList(
    cards: List<TagCardUi>,
    selectedBeaconId: String?,
    @Suppress("UNUSED_PARAMETER")
    fetchingBeaconIds: Set<String>,
    isRefreshing: Boolean,
    hasError: Boolean,
    onRefresh: () -> Unit,
    onManageTags: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenInfo: (String) -> Unit,
    onOpenHistory: (String, String) -> Unit,
    onOpenNearby: (() -> Unit)?,
    onRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val listHeight = with(density) { (windowInfo.containerSize.height * 0.45f).toDp() }
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Surface(
        modifier = modifier.height(listHeight),
        color = containerColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 14.dp,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Tags",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${cards.size}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.weight(1f))
                RefreshSpinButton(
                    isRefreshing = isRefreshing,
                    hasError = hasError,
                    onClick = onRefresh,
                )
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onManageTags() }
                        .testTag("btn_edit_tags"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Manage tags",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cards.size) { idx ->
                    val card = cards[idx]
                    TagGlassRow(
                        card = card,
                        isSelected = card.beaconId == selectedBeaconId,
                        onSelect = { onSelect(card.beaconId) },
                        onOpenInfo = { onOpenInfo(card.beaconId) },
                        onOpenHistory = { onOpenHistory(card.beaconId, card.displayName) },
                        onOpenNearby = onOpenNearby,
                        onRoute = { onRoute(card.beaconId) },
                    )
                }
            }
        }
    }
}

/**
 * Refresh icon button that spins around its visual center while
 * isRefreshing. Material's Filled.Refresh is roughly centered on the
 * vector viewbox (24x24), but the visible "C" of the arrow sits a
 * hair down-left because the arrowhead extends top-right. Pivoting
 * at TransformOrigin(0.5, 0.55) brings the rotation center onto the
 * arrow loop instead of the bbox midpoint, which removed the visible
 * wobble during spin.
 */
@Composable
private fun RefreshSpinButton(
    isRefreshing: Boolean,
    hasError: Boolean,
    onClick: () -> Unit,
) {
    // visibleSpin = isRefreshing OR a forced min-duration after each tap.
    // The min-duration loop MUST read the live isRefreshing, not the value
    // captured when the effect launched. LaunchedEffect is keyed only on
    // forceSpin, so a plain `while (isRefreshing)` froze the value at launch:
    // if it was true, the loop never exited, forceSpin stuck true, and the
    // button span forever AND went un-tappable (enabled = !spinning) even
    // after the refresh had finished. rememberUpdatedState fixes the capture.
    val liveRefreshing = androidx.compose.runtime.rememberUpdatedState(isRefreshing)
    var forceSpin by remember { mutableStateOf(false) }
    LaunchedEffect(forceSpin) {
        if (forceSpin) {
            kotlinx.coroutines.delay(900)
            while (liveRefreshing.value) kotlinx.coroutines.delay(150)
            forceSpin = false
        }
    }
    val spinning = isRefreshing || forceSpin

    // Frame-driven angle (NOT InfiniteTransition). InfiniteTransition
    // goes through Compose's MotionDurationScale, which is 0 on devices
    // with system-wide "Animator duration scale" turned off — that
    // collapses the tween to instant and the icon never visibly
    // rotates. Pulling time directly from withFrameNanos bypasses the
    // scale entirely, so the spin works regardless of system settings.
    var angle by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) {
            angle = 0f
            return@LaunchedEffect
        }
        var startNs = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { ns ->
                if (startNs == 0L) startNs = ns
                val elapsedMs = (ns - startNs) / 1_000_000L
                angle = ((elapsedMs / 900f) * 360f) % 360f
            }
        }
    }

    val dotColor = when {
        hasError -> androidx.compose.ui.graphics.Color(0xFFEF4444)        // red
        spinning -> androidx.compose.ui.graphics.Color(0xFFF59E0B)        // orange
        else -> androidx.compose.ui.graphics.Color(0xFF22C55E)            // green
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = !spinning) {
                forceSpin = true
                onClick()
            }
            .testTag("btn_refresh_all"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Refresh now",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .rotate(angle),
        )
        // Status dot — small, in the middle of the loop. Same pivot
        // as the rotation so it stays put while the arrow spins.
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun TagGlassRow(
    card: TagCardUi,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNearby: (() -> Unit)?,
    onRoute: () -> Unit,
) {
    val hasLocation = card.latitude != null && card.longitude != null
    val rowColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    }
    val rowBorder = if (isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onSelect() }
            .testTag("tag_row_${card.beaconId}"),
        color = rowColor,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = if (isSelected) 4.dp else 0.dp,
        border = rowBorder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Glyph chip
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val glyph = card.emoji ?: card.displayName.firstOrNull()?.uppercase() ?: "●"
                Text(glyph, fontSize = 22.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    card.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AddressLine(card.addressLine, hasLocation, card.latitude, card.longitude)
                Text(
                    lastUpdatedLabel(card.lastUpdatedMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Order: Info -> History -> Route. (User asked for
                // "i - history - route" because the prior list-list-route
                // sequence buried Info under History.)
                RowActionIcon(
                    icon = Icons.Filled.Info,
                    contentDescription = "Details",
                    onClick = onOpenInfo,
                    // Per-row suffix so E2E flows can target a specific
                    // tag's action deterministically (generic prefix
                    // matching still works via regex ids).
                    tag = "btn_card_details_${card.beaconId}",
                )
                RowActionIcon(
                    // Calendar matches the day picker icon used in the
                    // History screen header, so the affordance reads as
                    // "open history" instead of generic "list".
                    icon = Icons.Filled.CalendarMonth,
                    contentDescription = "History",
                    onClick = onOpenHistory,
                    tag = "btn_card_history_${card.beaconId}",
                )
                RowActionIcon(
                    icon = Icons.Filled.Directions,
                    contentDescription = "Route",
                    onClick = onRoute,
                    enabled = hasLocation,
                )
                if (onOpenNearby != null) {
                    RowActionIcon(
                        icon = Icons.Filled.BluetoothSearching,
                        contentDescription = "Nearby",
                        onClick = onOpenNearby,
                        tag = "btn_card_nearby",
                    )
                }
            }
        }
    }
}

@Composable
private fun RowActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface
               else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** "48.2094, 9.7203" — a location shown as coordinates when no street resolved. */
private fun coarseCoords(lat: Double, lon: Double): String {
    fun r(v: Double): String {
        val n = kotlin.math.round(v * 10_000.0).toLong()
        val whole = n / 10_000
        val frac = (kotlin.math.abs(n) % 10_000).toString().padStart(4, '0')
        return "$whole.$frac"
    }
    return "${r(lat)}, ${r(lon)}"
}

@Composable
private fun AddressLine(
    addressLine: String?,
    hasLocation: Boolean,
    latitude: Double?,
    longitude: Double?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (hasLocation) Icons.Filled.Place else Icons.Filled.LocationOff,
            contentDescription = null,
            modifier = Modifier.size(15.dp).height(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        val streetOnly = addressLine?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
        Text(
            when {
                !hasLocation -> "No recent location"
                streetOnly != null -> streetOnly
                // Located but the reverse-geocode has not come back (or the
                // platform has no geocoder): show the coordinates. "Locating…"
                // here reads as still-searching for a tag that is already found.
                latitude != null && longitude != null -> coarseCoords(latitude, longitude)
                else -> "Locating…"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTime::class)
private fun lastUpdatedLabel(lastUpdatedMs: Long?): String {
    if (lastUpdatedMs == null) return "Not yet reported"
    val delta = Clock.System.now().toEpochMilliseconds() - lastUpdatedMs
    val s = delta / 1_000
    return when {
        s < 0 -> "Updated just now"
        s < 60 -> "Updated just now"
        s < 3_600 -> "Updated ${s / 60} min ago"
        s < 86_400 -> "Updated ${s / 3_600} h ago"
        else -> "Updated ${s / 86_400} d ago"
    }
}

/**
 * Minimal M3 skeleton shimmer card shown while the first refresh is
 * in-flight and the DB cache was empty. Uses an infinite alpha pulse
 * on surfaceVariant — good-enough signal without adding dependencies.
 */
@Composable
private fun LoadingShimmerCard(modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "shimmer-alpha",
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .fillMaxWidth(0.5f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                        RoundedCornerShape(6.dp),
                    )
            )
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .fillMaxWidth(0.85f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                        RoundedCornerShape(6.dp),
                    )
            )
        }
    }
}

@Composable
internal fun BasemapCycleButton(
    current: MapBasemap,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (current) {
        MapBasemap.LIGHT -> Icons.Filled.LightMode
        MapBasemap.DARK -> Icons.Filled.DarkMode
        MapBasemap.SATELLITE -> Icons.Filled.Satellite
    }
    FilledIconButton(
        onClick = onCycle,
        modifier = modifier,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = "Basemap: ${current.name.lowercase()}")
    }
}

private const val CARD_WIDTH_FRACTION = 0.80f
private val CARD_HEIGHT = 220.dp
