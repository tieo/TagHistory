package io.github.tieo.taghistory.ui.nav

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform back gesture. Android hooks into the
 * Activity's OnBackPressedDispatcher via `androidx.activity.compose.BackHandler`;
 * desktop + iOS are no-ops because those platforms drive navigation via
 * window chrome / swipe gestures handled elsewhere.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
