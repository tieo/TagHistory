package io.github.tieo.taghistory.ui.information

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.tieo.taghistory.ui.theme.TagHistoryTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class InformationScreenTest {

    @BeforeTest
    fun setUpMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDownMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun information_screen_renders_app_title() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    InformationScreen(
                        versionName = "1.2.3",
                        onBack = {},
                        onOpenUrl = {},
                    )
                }
            }
        }
        onNodeWithText("TagHistory").assertIsDisplayed()
    }

    @Test
    fun information_screen_renders_version_string() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    InformationScreen(
                        versionName = "9.9.9",
                        onBack = {},
                        onOpenUrl = {},
                    )
                }
            }
        }
        onNodeWithText("Version 9.9.9").assertIsDisplayed()
    }

    @Test
    fun information_screen_renders_links_section() = runComposeUiTest {
        setContent {
            TagHistoryTheme {
                Surface {
                    InformationScreen(
                        versionName = "1.0.0",
                        onBack = {},
                        onOpenUrl = {},
                    )
                }
            }
        }
        onNodeWithText("Developer website").assertIsDisplayed()
        onNodeWithText("Source code").assertIsDisplayed()
        onNodeWithText("License").assertIsDisplayed()
    }

    @Test
    fun information_screen_link_click_invokes_callback() = runComposeUiTest {
        val openedUrls = mutableListOf<String>()
        setContent {
            TagHistoryTheme {
                Surface {
                    InformationScreen(
                        versionName = "1.0.0",
                        onBack = {},
                        onOpenUrl = { openedUrls += it },
                    )
                }
            }
        }
        onNodeWithTag("btn_source_code").performClick()
        assertEquals(1, openedUrls.size)
        assertTrue(openedUrls.first().contains("github.com"))
    }

    @Test
    fun information_screen_back_button_invokes_callback() = runComposeUiTest {
        var backCount = 0
        setContent {
            TagHistoryTheme {
                Surface {
                    InformationScreen(
                        versionName = "1.0.0",
                        onBack = { backCount++ },
                        onOpenUrl = {},
                    )
                }
            }
        }
        // The top app bar always has a back navigation icon in PushedScreenScaffold.
        onNodeWithText("About").assertIsDisplayed()
        // Back icon button has content description "Back".
        onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        assertEquals(1, backCount)
    }
}
