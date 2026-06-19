package eu.monniot.feed.ui.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
}
