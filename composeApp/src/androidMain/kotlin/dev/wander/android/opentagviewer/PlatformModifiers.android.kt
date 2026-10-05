package io.github.tieo.taghistory

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

actual fun Modifier.withTestTagsAsResourceId(): Modifier =
    semantics { testTagsAsResourceId = true }

@Composable
actual fun rootSurfaceColor(themeColor: Color): Color = themeColor
