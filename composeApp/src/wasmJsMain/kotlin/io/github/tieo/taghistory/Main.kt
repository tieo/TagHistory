package io.github.tieo.taghistory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.data.storage.SettingsFactory
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.db.createIdbBackedDriver
import io.github.tieo.taghistory.host.WasmAppHost
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import kotlinx.browser.document

/**
 * Browser entry. The sqljs driver init is `suspend`, so the root
 * Composable shows a spinner until the DB driver + WasmAppHost are
 * ready, then swaps in the full App with real factories.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        var factories by remember { mutableStateOf<AppHostFactories?>(null) }
        LaunchedEffect(Unit) {
            val driver = createIdbBackedDriver()
            val db = TagHistoryDatabase(driver)
            val host = WasmAppHost(
                db = db,
                settingsFactory = SettingsFactory(),
                crypto = SecureBlobStore(),
            )
            factories = host.buildFactories(appVersion = "wasm-preview")
        }
        val f = factories
        if (f == null) {
            TagHistoryTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            App(factories = f)
        }
    }
}
