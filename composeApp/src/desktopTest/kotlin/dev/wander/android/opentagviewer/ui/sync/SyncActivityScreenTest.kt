package io.github.tieo.taghistory.ui.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.runComposeUiTest
import io.github.tieo.taghistory.data.repo.SyncOutcome
import io.github.tieo.taghistory.data.repo.SyncRun
import io.github.tieo.taghistory.data.repo.SyncTrigger
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SyncActivityScreenTest {

    @Test
    fun runs_that_started_in_the_same_millisecond_both_render() = runComposeUiTest {
        // The periodic job and the alarm can start together; a list keyed by
        // start time rejects the second row with a crash.
        val t = 1_790_000_000_000L
        val runs = listOf(
            SyncRun(t, SyncTrigger.ALARM, SyncOutcome.SUCCESS, null, 3, 2, 2, 900),
            SyncRun(t, SyncTrigger.WORKER, SyncOutcome.SKIPPED, "throttled: 0m since last run", 0, 0, null, 5),
        )
        setContent { TagHistoryTheme { SyncActivityScreen(runs = runs, onBack = {}, nowMs = t + 60_000L) } }
        onAllNodesWithText("OK").assertCountEquals(1)
        onAllNodesWithText("SKIP").assertCountEquals(1)
        onNodeWithText("2 recorded run(s). Times are local.").assertIsDisplayed()
    }
}
