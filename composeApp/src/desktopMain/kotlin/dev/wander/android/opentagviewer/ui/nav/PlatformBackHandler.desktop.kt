package io.github.tieo.taghistory.ui.nav

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop has no OS back button; window close / swipe gestures are
    // out of scope. The stub keeps commonMain compiling.
}
