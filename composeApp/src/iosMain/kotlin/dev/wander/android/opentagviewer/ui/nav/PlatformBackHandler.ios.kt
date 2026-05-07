package io.github.tieo.taghistory.ui.nav

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS uses the swipe-from-edge gesture handled by UINavigationController.
    // No Compose-side hook needed for now.
}
