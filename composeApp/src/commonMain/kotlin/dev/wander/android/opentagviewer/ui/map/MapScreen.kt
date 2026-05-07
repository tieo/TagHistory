package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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
    onRoute: (lat: Double, lon: Double, label: String) -> Unit = { _, _, _ -> },
    onImport: (suspend () -> String?)? = null,
    /** In-foreground auto-refresh interval. */
    refreshIntervalMs: Long = 60_000L,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // VM init block already kicks off boot + refresh at construction so
    // first paint isn't blocked on Compose mount. Nothing to do here.

    LaunchedEffect(refreshIntervalMs) {
        while (true) {
            delay(refreshIntervalMs)
            viewModel.refresh()
        }
    }

    val refreshError = state.refreshError
    LaunchedEffect(refreshError, snackbarHostState) {
        val host = snackbarHostState ?: return@LaunchedEffect
        val msg = refreshError ?: return@LaunchedEffect
        host.showSnackbar(msg.take(80), duration = SnackbarDuration.Short)
    }

    val themeDefault = defaultBasemap()
    var basemap by remember(themeDefault) { mutableStateOf(themeDefault) }
    val haptics = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxSize()) {
        PlatformMapView(
            markers = state.markers,
            selectedBeaconId = state.selectedBeaconId,
            initialCamera = state.initialCamera,
            basemap = basemap,
            onMarkerClick = viewModel::selectBeacon,
            onCameraIdle = viewModel::saveCamera,
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
                TagCardPager(
                    cards = state.cards,
                    selectedBeaconId = state.selectedBeaconId,
                    onSelect = viewModel::selectBeacon,
                    onOpenInfo = onOpenDevice,
                    onOpenHistory = onOpenHistory,
                    onRoute = onRoute,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
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
                        CircularProgressIndicator(
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
 * HorizontalPager — one swipe advances exactly one card. Selected card
 * is always centered (via `contentPadding` = half-gap on each side). Cards
 * occupy [CARD_WIDTH_FRACTION] of the parent. Scroll → onSelect; external
 * onSelect → animateScrollToPage for two-way binding with the map.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun TagCardPager(
    cards: List<TagCardUi>,
    selectedBeaconId: String?,
    onSelect: (String) -> Unit,
    onOpenInfo: (String) -> Unit,
    onOpenHistory: (String, String) -> Unit,
    onRoute: (Double, Double, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.height(CARD_HEIGHT)) {
        val parentWidth = maxWidth
        val pageWidth = parentWidth * CARD_WIDTH_FRACTION
        val sidePadding = (parentWidth - pageWidth) / 2

        val initialIndex = cards.indexOfFirst { it.beaconId == selectedBeaconId }
            .takeIf { it >= 0 } ?: 0
        val pagerState = rememberPagerState(
            initialPage = initialIndex,
            pageCount = { cards.size },
        )

        // External selection change (map tap, boot auto-select) →
        // animate pager to that page.
        LaunchedEffect(selectedBeaconId, cards) {
            if (selectedBeaconId == null) return@LaunchedEffect
            val idx = cards.indexOfFirst { it.beaconId == selectedBeaconId }
            if (idx >= 0 && idx != pagerState.currentPage) {
                pagerState.animateScrollToPage(idx)
            }
        }

        // Drive the ViewModel (map highlight + camera) only when the pager
        // has fully settled. Using currentPage here caused a feedback loop:
        // rapid swipes → onSelect fires mid-gesture → LaunchedEffect above
        // tries to animateScrollToPage back → fights the user's gesture →
        // desync between visible card and highlighted marker.
        //
        // rememberUpdatedState: the guard must read the *live* selectedBeaconId,
        // not the one captured when the LaunchedEffect first started. Without
        // this, swipe A→B→A causes settledPage=A to fire, but the closure still
        // sees the original selectedBeaconId=A, so id==selectedBeaconId → onSelect
        // skipped → map stuck on B forever.
        val currentSelectedId = androidx.compose.runtime.rememberUpdatedState(selectedBeaconId)
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(pagerState, cards) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    cards.getOrNull(page)?.beaconId?.let { id ->
                        if (id != currentSelectedId.value) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(id)
                        }
                    }
                }
        }

        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = sidePadding),
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val card = cards[index]
            // Border tracks the visually current page directly — no ViewModel
            // roundtrip. selectedBeaconId lags during rapid swipes so using it
            // here caused the border to appear on an off-screen card.
            TagCard(
                card = card,
                isSelected = index == pagerState.currentPage,
                onOpenInfo = { onOpenInfo(card.beaconId) },
                onOpenHistory = { onOpenHistory(card.beaconId, card.displayName) },
                onRoute = {
                    val lat = card.latitude
                    val lon = card.longitude
                    if (lat != null && lon != null) onRoute(lat, lon, card.displayName)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun TagCard(
    card: TagCardUi,
    isSelected: Boolean,
    onOpenInfo: () -> Unit,
    onOpenHistory: () -> Unit,
    onRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasLocation = card.latitude != null && card.longitude != null
    // Translucent card — map faintly visible through, matches modern
    // "floating glass" pattern. Selected state gets an accent border
    // instead of a full color-flip (less visual noise).
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val border = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

    Card(
        modifier = modifier
            // All-around drop shadow (not just below). Compose's default
            // Card elevation creates a Y-biased Android shadow; this
            // explicit `shadow` modifier uses spread+blur on every side.
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            ),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
        ),
        border = border,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header: emoji avatar + (name / time-ago) stack.
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiAvatar(card.emoji, card.displayName, hasLocation)
                Spacer(Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        card.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = buildString {
                        if (!card.model.isNullOrBlank()) append(card.model)
                        val timeLabel = lastUpdatedLabel(card.lastUpdatedMs)
                        if (isNotEmpty()) append(" · $timeLabel") else append(timeLabel)
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Address row aligned flush with the card edge — previously sat
            // under the emoji which looked off. Icon + text together form
            // one left-aligned strip under the header block.
            AddressLine(card.addressLine, hasLocation)

            Spacer(Modifier.weight(1f))

            // Action row: History + Details live left, Route (primary) right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardTextAction(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "History",
                    onClick = onOpenHistory,
                    enabled = hasLocation,
                    tag = "btn_card_history",
                )
                CardTextAction(
                    icon = Icons.Filled.Info,
                    label = "Details",
                    onClick = onOpenInfo,
                    tag = "btn_card_details",
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onRoute,
                    enabled = hasLocation,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Directions,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Route", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun EmojiAvatar(
    emoji: String?,
    displayName: String,
    hasLocation: Boolean,
) {
    val containerColor = if (hasLocation) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(color = containerColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = emoji ?: displayName.firstOrNull()?.uppercase() ?: "●"
        Text(glyph, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun AddressLine(addressLine: String?, hasLocation: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (hasLocation) Icons.Filled.Place else Icons.Filled.LocationOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        // Address can be "Max-Planck-Straße 20, 89584 Ehingen (Donau), Germany".
        // Show only the first comma segment — street + number — which is
        // what the user cares about glance-wise.
        val streetOnly = addressLine?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
        Text(
            when {
                !hasLocation -> "No recent location"
                streetOnly != null -> streetOnly
                else -> "Locating…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardTextAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
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
private fun BasemapCycleButton(
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
