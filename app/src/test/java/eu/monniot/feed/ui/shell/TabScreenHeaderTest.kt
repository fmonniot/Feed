package eu.monniot.feed.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TabScreenHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Header: subtitle shows correct unread/total counts
    // ---------------------------------------------------------------------------

    @Test
    fun headerShowsSubtitleWithCounts() {
        composeTestRule.setContent {
            FeedTheme {
                TabScreenHeader(
                    title = "Unread",
                    subtitle = "3 unread · 4 total",
                )
            }
        }

        composeTestRule.onNodeWithText("3 unread · 4 total").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // SyncErrorRow: banner appears when rendered as trailing content
    // ---------------------------------------------------------------------------

    @Test
    fun syncErrorRow_bannerIsDisplayed() {
        composeTestRule.setContent {
            FeedTheme {
                TabScreenHeader(
                    title = "Unread",
                    subtitle = "3 unread · 4 total",
                ) {
                    SyncErrorRow(onRetry = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Last sync failed", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // SyncErrorRow: tapping Retry invokes the callback
    // ---------------------------------------------------------------------------

    @Test
    fun syncErrorRow_retryClickInvokesCallback() {
        var retryInvoked = false

        composeTestRule.setContent {
            FeedTheme {
                TabScreenHeader(
                    title = "Unread",
                    subtitle = "3 unread · 4 total",
                ) {
                    SyncErrorRow(onRetry = { retryInvoked = true })
                }
            }
        }

        composeTestRule.onNodeWithText("Retry").performClick()
        assertTrue(retryInvoked)
    }

    // ---------------------------------------------------------------------------
    // SyncErrorRow: banner absent when trailing content is empty
    // ---------------------------------------------------------------------------

    @Test
    fun syncErrorRow_absentWhenNoTrailingContent() {
        composeTestRule.setContent {
            FeedTheme {
                TabScreenHeader(
                    title = "Unread",
                    subtitle = "3 unread · 4 total",
                )
            }
        }

        composeTestRule.onNodeWithText("Last sync failed", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------
    // Actions slot: icon button is displayed and clickable (#69)
    // ---------------------------------------------------------------------------

    @Test
    fun actionsSlot_addFeedButtonIsDisplayed() {
        composeTestRule.setContent {
            FeedTheme {
                TabScreenHeader(
                    title = "Feeds",
                    subtitle = "5 subscriptions",
                    actions = {
                        IconButton(
                            onClick = {},
                            modifier = Modifier.testTag("add_feed_action"),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add feed")
                        }
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("add_feed_action").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add feed").assertIsDisplayed()
    }

    @Test
    fun actionsSlot_addFeedButtonInvokesCallback() {
        var clicked = false

        composeTestRule.setContent {
            FeedTheme {
                TabScreenHeader(
                    title = "Feeds",
                    subtitle = "5 subscriptions",
                    actions = {
                        IconButton(
                            onClick = { clicked = true },
                            modifier = Modifier.testTag("add_feed_action"),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add feed")
                        }
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("add_feed_action").performClick()
        assertTrue(clicked)
    }
}
