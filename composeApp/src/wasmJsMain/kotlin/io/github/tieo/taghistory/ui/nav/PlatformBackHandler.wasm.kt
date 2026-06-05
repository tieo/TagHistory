package io.github.tieo.taghistory.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Wires the browser's back gesture (popstate) into the in-app nav
 * stack. When a screen registers a back handler, we push a marker
 * onto history.state so the next popstate fires our callback instead
 * of unloading the page. On dispose we walk back one entry to drop
 * the marker — keeps history clean across navigations.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }
        pushHistoryMarker()
        val unregister = registerPopState { currentOnBack() }
        onDispose {
            unregister()
            // Best-effort: drop the marker we pushed. If the user
            // already navigated forward this is a no-op.
            popHistoryMarker()
        }
    }
}

private fun pushHistoryMarker() {
    js("history.pushState({ taghistoryBack: true }, '')")
}

private fun popHistoryMarker() {
    js("if (history.state && history.state.taghistoryBack) history.back()")
}

private fun registerPopState(callback: () -> Unit): () -> Unit {
    val handler: () -> Unit = { callback() }
    jsAddListener(handler)
    return { jsRemoveListener(handler) }
}

private fun jsAddListener(handler: () -> Unit) {
    js("window.__taghistoryBack__ = handler; window.addEventListener('popstate', window.__taghistoryBack__)")
}

private fun jsRemoveListener(handler: () -> Unit) {
    js("if (window.__taghistoryBack__) { window.removeEventListener('popstate', window.__taghistoryBack__); window.__taghistoryBack__ = null }")
}
