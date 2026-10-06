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
import dev.kilua.form.select.select
import dev.kilua.html.a
import dev.kilua.html.button
import dev.kilua.html.div
import dev.kilua.html.li
import dev.kilua.html.span
import dev.kilua.html.ul
import io.github.tieo.taghistory.ui.history.HistoryEntry
import io.github.tieo.taghistory.ui.history.HistoryViewModel
import io.github.tieo.taghistory.ui.history.RenderedItem
import io.github.tieo.taghistory.ui.history.buildDayBuckets
import io.github.tieo.taghistory.ui.history.buildDaySummary
import io.github.tieo.taghistory.ui.history.buildRenderedItems
import io.github.tieo.taghistory.ui.history.commonCityOrNull
import io.github.tieo.taghistory.ui.history.dayLabel
import io.github.tieo.taghistory.ui.history.entryAnchor
import io.github.tieo.taghistory.ui.history.formatLocalTime
import io.github.tieo.taghistory.ui.history.localDayStart
import io.github.tieo.taghistory.ui.history.parseAddress
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.TagCardUi
import io.github.tieo.taghistory.ui.util.fmtFixed
import io.github.tieo.taghistory.ui.util.formatDistance
import io.github.tieo.taghistory.ui.util.formatDuration
import io.github.tieo.taghistory.web.host.nowMs
import io.github.tieo.taghistory.web.map.MapInsets
import io.github.tieo.taghistory.web.map.historyMap
import io.github.tieo.taghistory.web.map.next
import kotlinx.browser.window

/**
 * One tag's history, a day at a time: the day's track on the map, and in the
 * panel the day's summary and its stops and moves, newest first, with the
 * distance and time between them. Selecting a row or a fix on the map selects
 * it in both.
 */
@Composable
fun IComponent.historyPage(vm: HistoryViewModel, beaconId: String, tags: List<TagCardUi>, dark: Boolean) {
    val state by vm.state.collectAsState()
    val breakpoint by rememberTailwindcssBreakpoint()
    val wide = breakpoint isGreaterThan TailwindcssBreakpoint.SM
    var basemap by remember(dark) { mutableStateOf(if (dark) MapBasemap.DARK else MapBasemap.LIGHT) }
    LaunchedEffect(vm) { vm.load(0L, Long.MAX_VALUE) }

    val days = remember(state.points) { buildDayBuckets(state.points) }
    var dayKey by remember(beaconId) { mutableStateOf<Long?>(null) }
    val dayIndex = days.indexOfFirst { it.key == dayKey }.takeIf { it >= 0 } ?: 0
    val day = days.getOrNull(dayIndex)
    val chronological = remember(day) { day?.points?.sortedBy { it.timestampMs }.orEmpty() }
    // Only the shown day lists addresses, so only its fixes are geocoded.
    LaunchedEffect(day?.points) { vm.setVisiblePoints(day?.points?.mapTo(HashSet()) { it.id }.orEmpty()) }
    val dayEntries = remember(state.entries, day) {
        if (day == null) emptyList() else state.entries.filter { localDayStart(it.timestampMs) == day.key }
    }
    val hideCity = remember(dayEntries) { commonCityOrNull(dayEntries) != null }
    var selectedId by remember(beaconId, day?.key) { mutableStateOf<String?>(null) }
    val selected = chronological.firstOrNull { it.id == selectedId } ?: chronological.lastOrNull()
    val tag = tags.firstOrNull { it.beaconId == beaconId }

    div("fixed inset-0 overflow-hidden bg-zinc-200 dark:bg-zinc-900") {
        historyMap(
            points = chronological,
            selectedId = selected?.id,
            basemap = basemap,
            insets = if (wide) MapInsets(left = 384 + 32) else MapInsets(bottom = (window.innerHeight * 0.5).toInt()),
            onPointSelected = { selectedId = it },
        )
        button(
            className = "absolute right-2.5 top-[107px] flex h-[29px] w-[29px] items-center justify-center rounded " +
                "bg-white text-[17px] text-zinc-800 shadow-[0_0_0_2px_rgba(0,0,0,0.1)] hover:bg-zinc-100",
        ) {
            attribute("type", "button")
            title("Map style: ${basemap.name.lowercase()}")
            ariaLabel("Map style: ${basemap.name.lowercase()}")
            onClick { basemap = basemap.next() }
            icon("layers")
        }

        div(
            "absolute flex flex-col overflow-hidden bg-white/95 shadow-xl ring-1 ring-black/5 backdrop-blur " +
                "dark:bg-zinc-900/95 dark:ring-white/10 " +
                if (wide) "left-4 top-4 bottom-4 w-96 rounded-2xl" else "inset-x-0 bottom-0 h-[50vh] rounded-t-2xl",
        ) {
            div("flex items-center gap-1 px-2 pt-2") {
                iconLink("arrow-left", "Back to the map", "/", testId = "btn_back")
                tagGlyph(tag?.emoji, tag?.displayName ?: "", size = "h-8 w-8 text-base")
                if (tags.size > 1) {
                    select(
                        options = tags.map { it.beaconId to it.displayName },
                        value = beaconId,
                        className = "min-w-0 flex-1 truncate rounded-lg bg-transparent px-2 py-1 text-base font-semibold " +
                            "outline-none hover:bg-zinc-100 focus-visible:ring-2 focus-visible:ring-violet-500 dark:hover:bg-zinc-800",
                    ) {
                        attribute("data-testid", "history_title_chip")
                        ariaLabel("Tag")
                        onChange { value?.let { if (it != beaconId) navigate("/tags/$it/history") } }
                    }
                } else {
                    span("min-w-0 flex-1 truncate px-2 text-base font-semibold") { +(tag?.displayName ?: "") }
                }
                selected?.let { p ->
                    a("https://www.openstreetmap.org/directions?to=${p.latitude}%2C${p.longitude}", className = ICON_BUTTON) {
                        attribute("target", "_blank")
                        attribute("rel", "noopener")
                        attribute("data-testid", "btn_route_to_selected")
                        title("Route to the selected fix")
                        ariaLabel("Route to the selected fix")
                        icon("navigation")
                    }
                }
            }
            when {
                !state.hasLoaded -> div("px-4 py-6 text-sm text-zinc-500") { +"Loading…" }
                days.isEmpty() || day == null -> div("px-4 py-6 text-sm text-zinc-500") { +"No locations recorded for this tag yet." }
                else -> {
                    dayBar(days.map { it.key }, dayIndex) { dayKey = it }
                    summaryStrip(chronological)
                    ul("min-h-0 flex-1 overflow-y-auto px-2 pb-3") {
                        for (item in buildRenderedItems(dayEntries)) {
                            when (item) {
                                is RenderedItem.LegItem -> legLabel(item.distanceMeters, item.durationMs)
                                is RenderedItem.EntryItem -> entryRow(item.entry, item.idx, selected?.id, hideCity) { selectedId = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Previous and next day around a list of every day that has fixes, newest first. */
@Composable
private fun IComponent.dayBar(keys: List<Long>, index: Int, onPick: (Long) -> Unit) {
    val now = nowMs()
    div("flex items-center gap-1 px-2 py-1") {
        iconButton("chevron-left", "Earlier day", disabled = index >= keys.lastIndex, testId = "btn_day_prev") {
            onPick(keys[index + 1])
        }
        select(
            options = keys.map { it.toString() to dayLabel(it, now, "Today", "Yesterday") },
            value = keys[index].toString(),
            className = "min-w-0 flex-1 rounded-lg bg-transparent px-2 py-1.5 text-center text-sm font-semibold outline-none " +
                "hover:bg-zinc-100 focus-visible:ring-2 focus-visible:ring-violet-500 dark:hover:bg-zinc-800",
        ) {
            attribute("data-testid", "history_day")
            ariaLabel("Day")
            onChange { value?.toLongOrNull()?.let(onPick) }
        }
        iconButton("chevron-right", "Later day", disabled = index <= 0, testId = "btn_day_next") {
            onPick(keys[index - 1])
        }
    }
}

@Composable
private fun IComponent.summaryStrip(points: List<io.github.tieo.taghistory.ui.history.HistoryPoint>) {
    val summary = buildDaySummary(points)
    div("mx-3 mb-2 grid grid-cols-4 gap-1 rounded-xl bg-zinc-100 p-2 text-center dark:bg-zinc-800/70") {
        stat(formatDistance(summary.distanceMeters), "distance")
        stat(formatDuration(summary.movingMs), "moving")
        stat(summary.stopCount.toString(), if (summary.stopCount == 1) "stop" else "stops")
        stat(points.size.toString(), if (points.size == 1) "fix" else "fixes")
    }
}

@Composable
private fun IComponent.stat(value: String, label: String) {
    div {
        div("text-sm font-semibold") { +value }
        div("text-[11px] text-zinc-500 dark:text-zinc-400") { +label }
    }
}

@Composable
private fun IComponent.legLabel(distanceMeters: Double, durationMs: Long) {
    li("flex items-center gap-2 py-1 pl-[3.25rem] text-xs text-zinc-500 dark:text-zinc-400") {
        icon("move-down", "text-[13px]")
        +"${formatDistance(distanceMeters)} · ${formatDuration(durationMs)}"
    }
}

@Composable
private fun IComponent.entryRow(entry: HistoryEntry, idx: Int, selectedId: String?, hideCity: Boolean, onSelect: (String) -> Unit) {
    val anchor = entryAnchor(entry)
    val isSelected = anchor.id == selectedId || (entry is HistoryEntry.Stop && entry.members.any { it.id == selectedId })
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val parsed = anchor.address?.let { parseAddress(it) }
    li(
        "rounded-xl px-2 py-2 transition " +
            if (isSelected) "bg-violet-50 ring-1 ring-violet-200 dark:bg-violet-950/40 dark:ring-violet-900" else "hover:bg-zinc-100 dark:hover:bg-zinc-800/60",
    ) {
        attribute("data-testid", "history_item_$idx")
        div("flex cursor-pointer items-start gap-3") {
            onClick { onSelect(anchor.id) }
            div("w-11 shrink-0 pt-0.5 text-sm font-semibold tabular-nums") { +formatLocalTime(anchor.timestampMs) }
            div("flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[13px] " +
                if (entry is HistoryEntry.Stop) "bg-violet-100 text-violet-700 dark:bg-violet-950 dark:text-violet-300" else "bg-zinc-200 text-zinc-600 dark:bg-zinc-700 dark:text-zinc-300") {
                icon(if (entry is HistoryEntry.Stop) "map-pin" else "footprints")
            }
            div("min-w-0 flex-1") {
                div("truncate text-sm font-medium") {
                    +(parsed?.street ?: "${anchor.latitude.fmtFixed(5)}, ${anchor.longitude.fmtFixed(5)}")
                }
                if (!hideCity) parsed?.city?.let { div("truncate text-xs text-zinc-500 dark:text-zinc-400") { +it } }
                val detail = when {
                    entry is HistoryEntry.Stop && entry.dwellMs > 0L ->
                        "${formatLocalTime(entry.arrivalMs)} → ${formatLocalTime(entry.departureMs)} · ${formatDuration(entry.dwellMs)}"
                    else -> accuracyLabel(anchor.horizontalAccuracy)
                }
                detail?.let { div("text-xs text-zinc-500 dark:text-zinc-400") { +it } }
            }
            if (entry is HistoryEntry.Stop && entry.members.size > 1) {
                iconButton(if (expanded) "chevron-up" else "chevron-down", if (expanded) "Collapse" else "Expand") { expanded = !expanded }
            }
        }
        if (expanded && entry is HistoryEntry.Stop) {
            ul("mt-1 flex flex-col pl-[3.25rem]") {
                for (p in entry.members.asReversed()) {
                    li(
                        "cursor-pointer rounded-md px-2 py-1 text-xs tabular-nums " +
                            if (p.id == selectedId) "bg-violet-100 dark:bg-violet-900/50" else "hover:bg-zinc-100 dark:hover:bg-zinc-800",
                    ) {
                        onClick { onSelect(p.id) }
                        +listOfNotNull(formatLocalTime(p.timestampMs), accuracyLabel(p.horizontalAccuracy)).joinToString(" · ")
                    }
                }
            }
        }
    }
}

/** "±40 m", or null for 0, which means the accuracy is unknown (fixes recovered without one). */
private fun accuracyLabel(meters: Long): String? = if (meters > 0) "±$meters m" else null
