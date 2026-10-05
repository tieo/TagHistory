package io.github.tieo.taghistory

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

actual fun Modifier.withTestTagsAsResourceId(): Modifier = this

@Composable
actual fun rootSurfaceColor(themeColor: Color): Color = themeColor
