package io.github.tieo.taghistory.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.kilua.core.IComponent
import dev.kilua.html.a
import dev.kilua.html.button
import dev.kilua.html.div
import dev.kilua.html.li
import dev.kilua.html.p
import dev.kilua.html.span
import dev.kilua.html.ul
import io.github.tieo.taghistory.data.repo.SyncOutcome
import io.github.tieo.taghistory.data.repo.SyncRun
import io.github.tieo.taghistory.ui.history.formatLocalDate
import io.github.tieo.taghistory.ui.history.formatLocalTimeWithSeconds
import io.github.tieo.taghistory.ui.settings.SettingsViewModel
import io.github.tieo.taghistory.ui.util.compactAgo
import io.github.tieo.taghistory.web.host.nowMs
import io.github.tieo.taghistory.web.host.openLoginPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Theme, the server's sync, data and the Apple account. */
@Composable
fun IComponent.settingsPage(vm: SettingsViewModel, onSyncNow: suspend () -> String) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmingSignOut by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.signedOut) { if (state.signedOut) openLoginPage() }

    page("Settings") {
        section("Appearance") {
            div("flex flex-col gap-2") {
                span("text-sm font-medium") { +"Theme" }
                div("grid grid-cols-3 gap-1 rounded-full bg-zinc-100 p-1 dark:bg-zinc-800") {
                    attribute("role", "radiogroup")
                    ariaLabel("Theme")
                    for ((label, value) in listOf("System" to null, "Light" to false, "Dark" to true)) {
                        val chosen = state.current.useDarkTheme == value
                        button(
                            className = "h-9 rounded-full text-sm font-semibold transition " +
                                if (chosen) "bg-white text-violet-700 shadow-sm dark:bg-zinc-950 dark:text-violet-300" else "text-zinc-600 hover:text-zinc-900 dark:text-zinc-300 dark:hover:text-white",
                        ) {
                            attribute("type", "button")
                            attribute("role", "radio")
                            attribute("aria-checked", chosen.toString())
                            attribute("data-testid", "btn_theme_${label.lowercase()}")
                            onClick { vm.setThemeMode(value) }
                            +label
                        }
                    }
                }
            }
        }

        section("Sync") {
            div("flex flex-wrap items-center gap-2") {
                textButton(if (syncing) "Syncing…" else "Sync now", BUTTON_PRIMARY, icon = "refresh-cw", disabled = syncing, testId = "btn_refresh_now") {
                    syncing = true
                    syncMessage = null
                    scope.launch {
                        syncMessage = runCatching { onSyncNow() }.getOrElse { "Sync failed: ${it.message ?: it::class.simpleName}" }
                        syncing = false
                    }
                }
                navLink("/settings/sync", BUTTON_SECONDARY) {
                    attribute("data-testid", "btn_sync_activity")
                    icon("activity")
                    span { +"Sync activity" }
                }
            }
            syncMessage?.let { p("text-sm text-zinc-600 dark:text-zinc-300") { attribute("data-testid", "refresh_message"); +it } }
        }

        section("Tags") {
            navLink("/tags", BUTTON_SECONDARY + " self-start") {
                icon("tags")
                span { +"Manage and import tags" }
            }
        }

        section("Account") {
            textButton("Sign out of Apple ID", BUTTON_DANGER, icon = "log-out", testId = "btn_sign_out", className = "self-start") {
                confirmingSignOut = true
            }
        }

        section("About") {
            navLink("/about", BUTTON_SECONDARY + " self-start") {
                attribute("data-testid", "btn_about")
                icon("info")
                span { +"About TagHistory" }
            }
        }
    }

    if (confirmingSignOut) {
        modal("Sign out?", onDismiss = { confirmingSignOut = false }, actions = {
            textButton("Cancel", testId = "btn_sign_out_cancel") { confirmingSignOut = false }
            textButton("Sign out", BUTTON_DANGER, testId = "btn_sign_out_confirm") {
                confirmingSignOut = false
                vm.signOut()
            }
        }) {
            p("text-sm text-zinc-600 dark:text-zinc-300") {
                +"The server signs out of your Apple account and stops syncing your tags until you sign in again."
            }
        }
    }
}

/** Every sync run the server recorded, newest first. */
@Composable
fun IComponent.syncActivityPage(runs: Flow<List<SyncRun>>) {
    val list by runs.collectAsState(emptyList())
    val now = nowMs()
    page("Sync activity", backHref = "/settings") {
        section(null) {
            val lastSuccess = list.firstOrNull { it.outcome == SyncOutcome.SUCCESS }
            div("text-base font-semibold") {
                +(lastSuccess?.let { "Last successful sync ${compactAgo(now - it.startedAtMs)} ago" } ?: "No successful sync recorded yet")
            }
            val lastEffective = list.firstOrNull { it.outcome != SyncOutcome.SKIPPED }
            if (lastEffective != null && lastEffective != lastSuccess) {
                div("text-sm text-zinc-500") {
                    +"Last attempt ${compactAgo(now - lastEffective.startedAtMs)} ago (${lastEffective.outcome.name.lowercase()})"
                }
            }
            div("text-xs text-zinc-500") { +"${list.size} recorded run${if (list.size == 1) "" else "s"}. Times are local." }
        }
        if (list.isEmpty()) return@page
        ul("flex flex-col divide-y divide-zinc-200 overflow-hidden rounded-2xl bg-white ring-1 ring-zinc-200 dark:divide-zinc-800 dark:bg-zinc-900 dark:ring-zinc-800") {
            attribute("data-testid", "sync_activity_list")
            for (run in list) {
                li("flex items-center gap-3 px-4 py-3") {
                    div("min-w-0 flex-1") {
                        div("font-mono text-sm") { +"${formatLocalDate(run.startedAtMs)}  ${formatLocalTimeWithSeconds(run.startedAtMs)}" }
                        div("truncate text-xs text-zinc-500 dark:text-zinc-400") {
                            +buildString {
                                append(run.trigger.name.lowercase())
                                when (run.outcome) {
                                    SyncOutcome.SUCCESS -> append(" · ${run.persistedReports} report(s), ${run.beaconCount} tag(s)")
                                    SyncOutcome.RETRY -> append(" · ${run.detail ?: "failed"}")
                                    SyncOutcome.SKIPPED -> append(" · ${run.detail ?: "skipped"}")
                                }
                                run.windowHours?.let { append(" · ${it}h window") }
                            }
                        }
                    }
                    val (label, style) = when (run.outcome) {
                        SyncOutcome.SUCCESS -> "OK" to "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300"
                        SyncOutcome.RETRY -> "RETRY" to "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300"
                        SyncOutcome.SKIPPED -> "SKIP" to "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300"
                    }
                    span("shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold $style") { +label }
                }
            }
        }
    }
}

/** Links and the map credits. */
@Composable
fun IComponent.aboutPage() {
    page("About", backHref = "/settings") {
        section("Links") {
            for ((label, url) in listOf(
                "Developer website" to "https://github.com/tieo",
                "Source code" to "https://github.com/tieo/TagHistory",
                "License" to "https://github.com/tieo/TagHistory/blob/main/LICENSE",
            )) {
                a(url, className = "flex items-center justify-between text-sm font-medium text-violet-700 hover:underline dark:text-violet-300") {
                    attribute("target", "_blank")
                    attribute("rel", "noopener")
                    span { +label }
                    icon("external-link")
                }
            }
        }
        section("Map attributions") {
            for (line in listOf(
                "Light basemap: CartoDB Voyager, © CARTO, © OpenStreetMap contributors (ODbL).",
                "Dark basemap: CartoDB Dark Matter, © CARTO, © OpenStreetMap contributors.",
                "Satellite basemap: © Esri, Maxar, Earthstar Geographics, GIS User Community.",
                "Rendered with MapLibre GL.",
            )) {
                p("text-sm text-zinc-600 dark:text-zinc-300") { +line }
            }
        }
    }
}
