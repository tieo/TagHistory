package io.github.tieo.taghistory.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kilua.compose.adaptive.TailwindcssBreakpoint
import dev.kilua.compose.adaptive.rememberTailwindcssBreakpoint
import dev.kilua.core.IComponent
import dev.kilua.html.a
import dev.kilua.html.button
import dev.kilua.html.div
import dev.kilua.html.li
import dev.kilua.html.span
import dev.kilua.html.ul
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.map.TagCardUi
import io.github.tieo.taghistory.ui.util.coarseCoords
import io.github.tieo.taghistory.ui.util.lastUpdatedLabel
import io.github.tieo.taghistory.web.host.nowMs
import io.github.tieo.taghistory.web.map.MapInsets
import io.github.tieo.taghistory.web.map.next
import io.github.tieo.taghistory.web.map.tagMap
import kotlinx.browser.window
import kotlinx.coroutines.delay

/**
 * The start page: every tag on the map, and the list of tags in a panel
 * beside it (on a phone, under it). Selecting a tag in either place selects
 * it in both and moves the camera to it.
 */
@Composable
fun IComponent.mapPage(vm: MapViewModel, dark: Boolean, onSyncNow: suspend () -> String) {
    val state by vm.state.collectAsState()
    val breakpoint by rememberTailwindcssBreakpoint()
    val wide = breakpoint isGreaterThan TailwindcssBreakpoint.SM
    var basemap by remember(dark) { mutableStateOf(if (dark) MapBasemap.DARK else MapBasemap.LIGHT) }
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = nowMs()
        }
    }
    LaunchedEffect(Unit) {
        vm.refreshNames()
        vm.refresh()
    }

    div("fixed inset-0 overflow-hidden bg-zinc-200 dark:bg-zinc-900") {
        tagMap(
            markers = state.markers,
            selectedId = state.selectedBeaconId,
            initialCamera = state.initialCamera,
            basemap = basemap,
            insets = if (wide) MapInsets(left = PANEL_WIDTH + 32) else MapInsets(bottom = (window.innerHeight * 0.45).toInt()),
            onMarkerClick = vm::selectBeacon,
            onCameraIdle = vm::saveCamera,
        )
        // Sits under MapLibre's zoom and compass group and looks like it.
        button(
            className = "absolute right-2.5 top-[107px] flex h-[29px] w-[29px] items-center justify-center rounded " +
                "bg-white text-[17px] text-zinc-800 shadow-[0_0_0_2px_rgba(0,0,0,0.1)] hover:bg-zinc-100",
        ) {
            attribute("type", "button")
            attribute("data-testid", "btn_basemap")
            title("Map style: ${basemap.name.lowercase()}")
            ariaLabel("Map style: ${basemap.name.lowercase()}")
            onClick { basemap = basemap.next() }
            icon("layers")
        }

        div(
            "absolute flex flex-col overflow-hidden bg-white/95 shadow-xl ring-1 ring-black/5 backdrop-blur " +
                "dark:bg-zinc-900/95 dark:ring-white/10 " +
                if (wide) "left-4 top-4 bottom-4 w-96 rounded-2xl" else "inset-x-0 bottom-0 max-h-[45vh] rounded-t-2xl",
        ) {
            panelHeader(state.cards.size, state.isRefreshing, state.refreshError != null, onRefresh = vm::refresh)
            state.refreshError?.let { error ->
                div("mx-4 mb-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800 dark:bg-red-950/50 dark:text-red-200") {
                    attribute("role", "alert")
                    +error
                }
            }
            when {
                state.cards.isEmpty() && (state.isRefreshing || !state.isInitialFetchComplete) ->
                    div("px-4 pb-4 text-sm text-zinc-500") {
                        attribute("data-testid", "map_loading_placeholder")
                        +"Loading tags…"
                    }
                state.cards.isEmpty() -> emptyTags()
                else -> ul("min-h-0 flex-1 overflow-y-auto px-2 pb-2") {
                    for (card in state.cards) {
                        tagRow(card, card.beaconId == state.selectedBeaconId, card.beaconId in state.fetchingBeaconIds, now, vm::selectBeacon)
                    }
                }
            }
        }
    }
}

// The panel is w-96 (384px) wide on wide screens.
private const val PANEL_WIDTH = 384

@Composable
private fun IComponent.panelHeader(count: Int, refreshing: Boolean, failed: Boolean, onRefresh: () -> Unit) {
    div("flex items-center gap-1 px-4 pb-2 pt-3") {
        span("icon-map-pin text-xl text-violet-600 dark:text-violet-400") { attribute("aria-hidden", "true") }
        span("ml-1 text-base font-semibold") { +"TagHistory" }
        span("ml-1 rounded-full bg-zinc-100 px-2 text-xs font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300") {
            +"$count"
        }
        div("flex-1") {}
        iconButton(
            "refresh-cw",
            if (failed) "Refresh failed, try again" else "Refresh",
            disabled = refreshing,
            testId = "btn_refresh_all",
            className = if (refreshing) "[&>span]:animate-spin" else if (failed) "text-red-600 dark:text-red-400" else "",
            onClick = onRefresh,
        )
        iconLink("pencil", "Manage tags", "/tags", testId = "btn_edit_tags")
        iconLink("settings", "Settings", "/settings", testId = "btn_settings")
    }
}

@Composable
private fun IComponent.emptyTags() {
    div("flex flex-col items-start gap-3 px-4 pb-5 text-sm text-zinc-600 dark:text-zinc-300") {
        +"No tags yet. Import an OpenTagViewer export to add them."
        navLink("/tags", BUTTON_PRIMARY) {
            attribute("data-testid", "btn_import")
            icon("upload")
            span { +"Import tags" }
        }
    }
}

@Composable
private fun IComponent.tagRow(card: TagCardUi, selected: Boolean, fetching: Boolean, now: Long, onSelect: (String) -> Unit) {
    li(
        "group flex cursor-pointer items-center gap-3 rounded-xl px-2 py-2 transition " +
            if (selected) "bg-violet-50 ring-1 ring-violet-200 dark:bg-violet-950/40 dark:ring-violet-900" else "hover:bg-zinc-100 dark:hover:bg-zinc-800/60",
    ) {
        attribute("data-testid", "tag_row_${card.beaconId}")
        attribute("aria-selected", selected.toString())
        onClick { onSelect(card.beaconId) }
        tagGlyph(card.emoji, card.displayName)
        div("min-w-0 flex-1") {
            div("truncate text-sm font-semibold") { +card.displayName }
            div("flex items-center gap-1 truncate text-xs text-zinc-500 dark:text-zinc-400") {
                val hasLocation = card.latitude != null && card.longitude != null
                icon(if (hasLocation) "map-pin" else "map-pin-off", "text-[13px]")
                span("truncate") {
                    +when {
                        !hasLocation && fetching -> "Locating…"
                        !hasLocation -> "No recent location"
                        else -> card.addressLine?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: coarseCoords(card.latitude!!, card.longitude!!)
                    }
                }
            }
            div("text-xs text-zinc-400 dark:text-zinc-500") { +lastUpdatedLabel(card.lastUpdatedMs, now) }
        }
        div("flex shrink-0 items-center") {
            iconLink("info", "Details", "/tags/${card.beaconId}", testId = "btn_card_details_${card.beaconId}")
            iconLink("history", "History", "/tags/${card.beaconId}/history", testId = "btn_card_history_${card.beaconId}")
            val lat = card.latitude
            val lon = card.longitude
            if (lat == null || lon == null) {
                // Keeps the other actions in their column.
                span("inline-block h-10 w-10") {}
            } else {
                a("https://www.openstreetmap.org/directions?to=$lat%2C$lon", className = ICON_BUTTON) {
                    attribute("target", "_blank")
                    attribute("rel", "noopener")
                    title("Route here")
                    ariaLabel("Route here")
                    onClick { it.stopPropagation() }
                    icon("navigation")
                }
            }
        }
    }
}
