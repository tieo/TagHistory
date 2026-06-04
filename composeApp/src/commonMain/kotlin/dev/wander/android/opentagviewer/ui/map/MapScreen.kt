package io.github.tieo.taghistory.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
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
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val listHeightDp = configuration.screenHeightDp.dp * 0.45f
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
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val listHeight = configuration.screenHeightDp.dp * 0.45f
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
                Spacer(Modifier.weight(1f))
                Text(
                    "${cards.size}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(4.dp))
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
    // Without the forced floor, a seeded/local-only refresh that
    // completes in <100 ms never gives the user a visible spin — that's
    // why the icon looked frozen even though state.isRefreshing was
    // technically flipping.
    var forceSpin by remember { mutableStateOf(false) }
    LaunchedEffect(forceSpin) {
        if (forceSpin) {
            // One full rotation minimum, then keep going while the
            // network is still in-flight.
            kotlinx.coroutines.delay(900)
            while (isRefreshing) kotlinx.coroutines.delay(150)
            forceSpin = false
        }
    }
    val spinning = isRefreshing || forceSpin

    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "refresh-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 900,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        ),
        label = "refresh-angle",
    )
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
                .rotate(if (spinning) angle else 0f),
        )
        // Status dot in the middle of the spinning loop. Same pivot
        // as the rotation so it stays put while the arrow spins.
        Box(
            modifier = Modifier
                .size(7.dp)
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
                AddressLine(card.addressLine, hasLocation)
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
                    tag = "btn_card_details",
                )
                RowActionIcon(
                    // Calendar matches the day picker icon used in the
                    // History screen header, so the affordance reads as
                    // "open history" instead of generic "list".
                    icon = Icons.Filled.CalendarMonth,
                    contentDescription = "History",
                    onClick = onOpenHistory,
                    tag = "btn_card_history",
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

/**
 * HorizontalPager — one swipe advances exactly one card. Selected card
 * is always centered (via `contentPadding` = half-gap on each side). Cards
 * occupy [CARD_WIDTH_FRACTION] of the parent. Scroll → onSelect; external
 * onSelect → animateScrollToPage for two-way binding with the map.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun TagCardPager(
    cards: List<TagCardUi>,
    selectedBeaconId: String?,
    onSelect: (String) -> Unit,
    onOpenInfo: (String) -> Unit,
    onOpenHistory: (String, String) -> Unit,
    onRoute: (Double, Double, String) -> Unit,
    fetchingBeaconIds: Set<String> = emptySet(),
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

        // Re-key only on the page index of the selected beacon, not the cards
        // list. Geocoding/refresh updates produce a new cards list reference
        // every tick — keying on cards restarted this effect (and the snapshot
        // collector below), causing spurious onSelect → haptic → animate loops.
        val selectedBeaconPage = remember(selectedBeaconId, cards) {
            if (selectedBeaconId == null) -1
            else cards.indexOfFirst { it.beaconId == selectedBeaconId }
        }
        // Track which beaconId triggered the last scroll so we can tell a
        // user-initiated selection change (animate) apart from a card list
        // reshuffle that moved the same beacon to a different index (snap).
        val lastScrolledBeaconId = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(selectedBeaconPage, selectedBeaconId) {
            if (selectedBeaconPage < 0 || selectedBeaconPage == pagerState.currentPage) {
                return@LaunchedEffect
            }
            if (lastScrolledBeaconId.value == selectedBeaconId) {
                // Same beacon, different index → cards reordered. Snap, no animation,
                // so the user keeps seeing their selected card without the visual
                // shuffle of cards sliding past.
                pagerState.scrollToPage(selectedBeaconPage)
            } else {
                pagerState.animateScrollToPage(selectedBeaconPage)
                lastScrolledBeaconId.value = selectedBeaconId
            }
        }

        val currentCards = androidx.compose.runtime.rememberUpdatedState(cards)
        val currentSelectedId = androidx.compose.runtime.rememberUpdatedState(selectedBeaconId)
        val currentOnSelect = androidx.compose.runtime.rememberUpdatedState(onSelect)
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    currentCards.value.getOrNull(page)?.beaconId?.let { id ->
                        if (id != currentSelectedId.value) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentOnSelect.value(id)
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
                isFetching = card.beaconId in fetchingBeaconIds,
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
    isFetching: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val hasLocation = card.latitude != null && card.longitude != null
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val shadowElevation = if (isSelected) 28.dp else 8.dp
    val shadowColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null

    Card(
        modifier = modifier.shadow(
            elevation = shadowElevation,
            shape = RoundedCornerShape(24.dp),
            ambientColor = shadowColor.copy(alpha = if (isSelected) 0.5f else 0.2f),
            spotColor = shadowColor.copy(alpha = if (isSelected) 0.6f else 0.3f),
        ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        border = border,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        if (isFetching) {
            AlwaysSpinningIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 18.dp)
                    .size(22.dp)
                    .testTag("card_fetching_${card.beaconId}"),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            // Top: emoji left, name/address/time right — matches original 85dp top row
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 15.dp, end = 15.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(70.dp).height(53.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val glyph = card.emoji ?: card.displayName.firstOrNull()?.uppercase() ?: "●"
                    Text(glyph, fontSize = 28.sp)
                }
                Column(
                    modifier = Modifier.weight(1f).padding(start = 15.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        card.displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AddressLine(card.addressLine, hasLocation)
                    Text(
                        lastUpdatedLabel(card.lastUpdatedMs),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Bottom: 3 filled-circle buttons centered — matches original 85dp bottom row
            Row(
                modifier = Modifier
                    .height(85.dp)
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardCircleAction(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "History",
                    onClick = onOpenHistory,
                    tag = "btn_card_history",
                )
                CardCircleAction(
                    icon = Icons.Filled.Info,
                    label = "Details",
                    onClick = onOpenInfo,
                    tag = "btn_card_details",
                )
                CardCircleAction(
                    icon = Icons.Filled.Directions,
                    label = "Route",
                    onClick = onRoute,
                    enabled = hasLocation,
                )
            }
        }
      }
    }
}

@Composable
private fun AddressLine(addressLine: String?, hasLocation: Boolean) {
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
                else -> "Locating…"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardCircleAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val circleColor = if (enabled) MaterialTheme.colorScheme.onBackground
                      else MaterialTheme.colorScheme.outlineVariant
    val iconColor = if (enabled) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.outline
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(80.dp)
            .clickable(enabled = enabled) { onClick() }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(color = circleColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = iconColor,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
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
