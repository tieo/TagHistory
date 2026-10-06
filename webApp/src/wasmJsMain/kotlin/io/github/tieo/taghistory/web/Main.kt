package io.github.tieo.taghistory.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.kilua.Application
import dev.kilua.LucideModule
import dev.kilua.TailwindcssModule
import dev.kilua.compose.root
import dev.kilua.html.div
import dev.kilua.html.span
import dev.kilua.startApplication
import dev.kilua.theme.ThemeManager
import io.github.tieo.taghistory.server.ServerClient
import io.github.tieo.taghistory.server.ServerStatus
import kotlinx.browser.window

class App : Application() {
    override fun start() {
        ThemeManager.init()
        root("root") {
            var status by remember { mutableStateOf<ServerStatus?>(null) }
            LaunchedEffect(Unit) { status = ServerClient(window.location.origin).status() }
            div("min-h-screen bg-white text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100 p-8") {
                div("flex items-center gap-2 text-lg font-semibold") {
                    span("icon-map-pin text-violet-500") {}
                    +"TagHistory"
                }
                div("mt-4 text-sm text-zinc-500") {
                    +(status?.let { "${it.beaconCount} tags, ${it.reportCount} reports" } ?: "Loading")
                }
            }
        }
    }
}

fun main() {
    startApplication(::App, null, TailwindcssModule, LucideModule)
}
