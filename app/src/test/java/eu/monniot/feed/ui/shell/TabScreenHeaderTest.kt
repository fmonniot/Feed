package eu.monniot.feed.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
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

    // ---------------------------------------------------------------------------
    // BUG-31: title vertical position must not shift when an actions button is
    // present. A plain Material3 IconButton claims a 48dp touch target, taller
    // than the 30sp title text; inside the header's vertically-centered Row,
    // that inflated row height pushed the "Feeds" title down relative to
    // "Unread" / "All" / "Settings" (none of which render an actions button).
    // Constraining the action IconButton to 32dp (matching the overflow-menu
    // convention in SubscriptionsScreen's FeedRow) keeps the row height — and
    // therefore the title's top position — identical across all four tabs.
    // ---------------------------------------------------------------------------

    @Test
    fun titleTopPosition_unaffectedByConstrainedActionsButton() {
        // Render both headers stacked in one composition (a single Activity can
        // only have setContent called on it once per test) and compare each
        // title's offset from the top of its own header container.
        //
        // NOTE: the `IconButton(... Modifier.size(32.dp) ...)` block below is a
        // duplicate of the real Feeds-tab `actions` slot in MainTabShell.kt (search
        // for "BUG-31" there). This test only pins TabScreenHeader's layout behavior
        // in isolation — a regression where someone drops `.size(32.dp)` from that
        // call site directly would NOT be caught here, since this copy wouldn't change.
        // MainTabShell itself has no test coverage (it requires a FeedViewModel +
        // NavController to render), so there's currently no way to pin the real call
        // site without standing up that harness.
        composeTestRule.setContent {
            FeedTheme {
                Column {
                    Box(modifier = Modifier.testTag("header_no_actions")) {
                        TabScreenHeader(
                            title = "Settings",
                            subtitle = "Signed in as admin",
                        )
                    }
                    Box(modifier = Modifier.testTag("header_with_constrained_actions")) {
                        TabScreenHeader(
                            title = "Feeds",
                            subtitle = "5 subscriptions",
                            actions = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("add_feed_action"),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add feed")
                                }
                            },
                        )
                    }
                }
            }
        }

        val noActionsHeaderTop = composeTestRule.onNodeWithTag("header_no_actions")
            .getUnclippedBoundsInRoot().top.value
        val settingsTitleTop = composeTestRule.onNodeWithText("Settings")
            .getUnclippedBoundsInRoot().top.value
        val withActionsHeaderTop = composeTestRule.onNodeWithTag("header_with_constrained_actions")
            .getUnclippedBoundsInRoot().top.value
        val feedsTitleTop = composeTestRule.onNodeWithText("Feeds")
            .getUnclippedBoundsInRoot().top.value

        val settingsTitleOffset = settingsTitleTop - noActionsHeaderTop
        val feedsTitleOffset = feedsTitleTop - withActionsHeaderTop

        assertEquals(settingsTitleOffset, feedsTitleOffset, 0.01f)
    }

    // Sanity check: without the 32dp constraint, a default IconButton's 48dp
    // touch target inflates the header Row and shifts the title down relative
    // to its own header container — this documents the bug that
    // titleTopPosition_unaffectedByConstrainedActionsButton guards against.
    @Test
    fun titleTopPosition_shiftsWithUnconstrainedActionsButton() {
        composeTestRule.setContent {
            FeedTheme {
                Column {
                    Box(modifier = Modifier.testTag("header_no_actions")) {
                        TabScreenHeader(
                            title = "Settings",
                            subtitle = "Signed in as admin",
                        )
                    }
                    Box(modifier = Modifier.testTag("header_with_unconstrained_actions")) {
                        TabScreenHeader(
                            title = "Feeds",
                            subtitle = "5 subscriptions",
                            actions = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.testTag("add_feed_action_unconstrained"),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add feed")
                                }
                            },
                        )
                    }
                }
            }
        }

        val noActionsHeaderTop = composeTestRule.onNodeWithTag("header_no_actions")
            .getUnclippedBoundsInRoot().top.value
        val settingsTitleTop = composeTestRule.onNodeWithText("Settings")
            .getUnclippedBoundsInRoot().top.value
        val withActionsHeaderTop = composeTestRule.onNodeWithTag("header_with_unconstrained_actions")
            .getUnclippedBoundsInRoot().top.value
        val feedsTitleTop = composeTestRule.onNodeWithText("Feeds")
            .getUnclippedBoundsInRoot().top.value

        val settingsTitleOffset = settingsTitleTop - noActionsHeaderTop
        val feedsTitleOffset = feedsTitleTop - withActionsHeaderTop

        assertTrue(
            "Expected unconstrained IconButton's 48dp touch target to push the title " +
                "down within its header relative to a header with no actions, but " +
                "offset was $feedsTitleOffset vs baseline $settingsTitleOffset",
            feedsTitleOffset > settingsTitleOffset,
        )
    }
}
