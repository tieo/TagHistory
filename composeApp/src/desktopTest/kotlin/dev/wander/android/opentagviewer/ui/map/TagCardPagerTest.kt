package io.github.tieo.taghistory.ui.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TagCardPagerTest {

    private fun card(id: String, name: String, tsMs: Long, address: String? = null) =
        TagCardUi(
            beaconId = id,
            displayName = name,
            emoji = "🔑",
            model = null,
            latitude = 52.5 + tsMs / 1e9,
            longitude = 13.4,
            lastUpdatedMs = tsMs,
            addressLine = address,
        )

    @Test
    fun pager_does_not_spuriously_select_when_only_address_lines_change() = runComposeUiTest {
        val initialCards = listOf(
            card("a", "Auto", 200L),
            card("b", "Bike", 100L),
        )
        var cards by mutableStateOf(initialCards)
        val onSelectCalls = mutableListOf<String>()
        var selectedId by mutableStateOf("a")

        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    TagCardPager(
                        cards = cards,
                        selectedBeaconId = selectedId,
                        onSelect = { id ->
                            onSelectCalls += id
                            selectedId = id
                        },
                        onOpenInfo = {},
                        onOpenHistory = { _, _ -> },
                        onRoute = { _, _, _ -> },
                    )
                }
            }
        }
        waitForIdle()
        val baseline = onSelectCalls.size

        // Simulate geocoding completing for each card: cards list reference
        // changes, addressLine is filled in, but ORDER stays the same.
        cards = listOf(
            card("a", "Auto", 200L, address = "Street A 1"),
            card("b", "Bike", 100L, address = "Street B 2"),
        )
        waitForIdle()
        cards = listOf(
            card("a", "Auto", 200L, address = "Street A 1, 12345 City"),
            card("b", "Bike", 100L, address = "Street B 2, 12345 City"),
        )
        waitForIdle()

        assertEquals("a", selectedId, "selectedId must stay on 'a' across geocode updates")
        assertEquals(
            baseline,
            onSelectCalls.size,
            "onSelect must NOT fire when only addressLines change. Calls: $onSelectCalls",
        )
    }

    @Test
    fun pager_does_not_loop_when_cards_reorder_then_address_updates() = runComposeUiTest {
        var cards by mutableStateOf(listOf(
            card("a", "Auto", 200L),
            card("b", "Bike", 100L),
        ))
        val onSelectCalls = mutableListOf<String>()
        var selectedId by mutableStateOf("a")

        setContent {
            TagHistoryTheme {
                Surface {
                    TagCardPager(
                        cards = cards,
                        selectedBeaconId = selectedId,
                        onSelect = { id ->
                            onSelectCalls += id
                            selectedId = id
                        },
                        onOpenInfo = {},
                        onOpenHistory = { _, _ -> },
                        onRoute = { _, _, _ -> },
                    )
                }
            }
        }
        waitForIdle()

        // Sort flips: Bike now newer than Auto.
        cards = listOf(
            card("b", "Bike", 300L),
            card("a", "Auto", 200L),
        )
        waitForIdle()
        // Geocode trickles in for both, list reference changes again.
        cards = listOf(
            card("b", "Bike", 300L, address = "Street B"),
            card("a", "Auto", 200L),
        )
        waitForIdle()
        cards = listOf(
            card("b", "Bike", 300L, address = "Street B"),
            card("a", "Auto", 200L, address = "Street A"),
        )
        waitForIdle()

        // The selected beacon ('a') stays selected — pager should follow it
        // to its new index, not jump to whatever beacon happens to be at the
        // old index.
        assertEquals("a", selectedId, "selectedId must follow beacon 'a' through reorder")
        assertTrue(
            onSelectCalls.size <= 1,
            "At most one onSelect allowed (for the reorder). Got: $onSelectCalls",
        )
    }
}
