package io.github.tieo.taghistory.ui.nav

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Browser back button handling would need window.history hooks;
    // skipped for the initial wasm scaffold.
}
