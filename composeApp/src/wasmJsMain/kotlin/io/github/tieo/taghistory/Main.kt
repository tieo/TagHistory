package io.github.tieo.taghistory

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * Browser entry point. Mounts the back-compat `App()` overload that
 * shows just the login screen with a placeholder view-model — the
 * full host with map / DB / sync factories needs wasm actuals that
 * the shared module hasn't shipped yet.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}
