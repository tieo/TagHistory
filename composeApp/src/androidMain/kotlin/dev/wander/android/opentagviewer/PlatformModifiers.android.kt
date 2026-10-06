package io.github.tieo.taghistory

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

actual fun Modifier.withTestTagsAsResourceId(): Modifier =
    semantics { testTagsAsResourceId = true }
