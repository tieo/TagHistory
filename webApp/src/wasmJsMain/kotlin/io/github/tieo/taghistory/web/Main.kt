package io.github.tieo.taghistory.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kilua.Application
import dev.kilua.LucideModule
import dev.kilua.TailwindcssModule
import dev.kilua.compose.root
import dev.kilua.html.div
import dev.kilua.routing.browserRouter
import dev.kilua.startApplication
import dev.kilua.theme.Theme
import dev.kilua.theme.ThemeManager
import io.github.tieo.taghistory.data.storage.SettingsFactory
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.createDatabase
import io.github.tieo.taghistory.server.ServerClient
import io.github.tieo.taghistory.web.host.WebHost
import io.github.tieo.taghistory.web.host.openLoginPage
import io.github.tieo.taghistory.web.ui.BUTTON_PRIMARY
import io.github.tieo.taghistory.web.ui.aboutPage
import io.github.tieo.taghistory.web.ui.deviceInfoPage
import io.github.tieo.taghistory.web.ui.manageTagsPage
import io.github.tieo.taghistory.web.ui.settingsPage
import io.github.tieo.taghistory.web.ui.syncActivityPage
import io.github.tieo.taghistory.web.ui.historyPage
import io.github.tieo.taghistory.web.ui.mapPage
import io.github.tieo.taghistory.web.ui.textButton
import kotlinx.browser.window

/**
 * Browser entry. Opens the local database, asks the server it was served
 * from whether it holds an Apple session (the sign-in page takes over when
 * it does not), and pulls what the server stored since the last visit before
 * the first page shows, so the map opens with every tag's newest position.
 */
class App : Application() {
    override fun start() {
        root("root") {
            var host by remember { mutableStateOf<WebHost?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var attempt by remember { mutableIntStateOf(0) }
            LaunchedEffect(attempt) {
                error = null
                try {
                    val client = ServerClient(window.location.origin)
                    if (!client.status().signedIn) {
                        openLoginPage()
                        return@LaunchedEffect
                    }
                    val db = createDatabase(DatabaseDriverFactory())
                    val webHost = WebHost(db, SettingsFactory(), client)
                    runCatching { webHost.replicator.pull() }
                    host = webHost
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = e.message ?: e::class.simpleName
                }
            }
            val h = host
            if (h == null) {
                div("flex min-h-screen flex-col items-center justify-center gap-4 bg-zinc-50 p-6 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100") {
                    val message = error
                    if (message == null) {
                        div("h-8 w-8 animate-spin rounded-full border-4 border-violet-200 border-t-violet-600") {
                            attribute("role", "progressbar")
                            ariaLabel("Loading")
                        }
                    } else {
                        div("text-lg font-semibold") { +"Can't reach the TagHistory server" }
                        div("text-sm text-zinc-500") { +message }
                        textButton("Retry", BUTTON_PRIMARY) { attempt++ }
                    }
                }
                return@root
            }
            val settings by h.userSettingsRepo.flow.collectAsState()
            LaunchedEffect(settings.useDarkTheme) {
                ThemeManager.theme = when (settings.useDarkTheme) {
                    null -> Theme.Auto
                    true -> Theme.Dark
                    false -> Theme.Light
                }
            }
            val dark = settings.useDarkTheme ?: window.matchMedia("(prefers-color-scheme: dark)").matches
            val mapState by h.map.state.collectAsState()
            browserRouter {
                route("/settings") {
                    route("/sync") { view { syncActivityPage(h.syncRuns()) } }
                    view { settingsPage(remember { h.settings() }, h::syncNow) }
                }
                route("/about") { view { aboutPage() } }
                route("/tags") {
                    string { tag ->
                        route("/history") {
                            view {
                                val vm = remember(tag.value) { h.history(tag.value) }
                                historyPage(vm, tag.value, mapState.cards, dark)
                            }
                        }
                        view {
                            val vm = remember(tag.value) { h.deviceInfo(tag.value) }
                            deviceInfoPage(vm, onRemoved = h.map::reboot)
                        }
                    }
                    view { manageTagsPage(h.map, h::importFromFile) }
                }
                // The root, and any path no route above knows: the map.
                view { mapPage(h.map, dark, h::syncNow) }
            }
        }
    }
}

fun main() {
    ThemeManager.init(remember = false)
    startApplication(::App, null, TailwindcssModule, LucideModule)
}
