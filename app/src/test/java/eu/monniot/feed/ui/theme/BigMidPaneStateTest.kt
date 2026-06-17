package eu.monniot.feed.ui.theme

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BigMidPaneStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── (a) Mandatory slots ───────────────────────────────────────────────────

    @Test
    fun mandatorySlots_eyebrowRenders() {
        composeTestRule.setContent {
            FeedTheme { BigMidPaneState(eyebrow = "ERR · CONN_REFUSED", title = "T.", body = "B.") }
        }
        composeTestRule.onNodeWithText("ERR · CONN_REFUSED").assertIsDisplayed()
    }

    @Test
    fun mandatorySlots_titleRenders() {
        composeTestRule.setContent {
            FeedTheme { BigMidPaneState(eyebrow = "ERR", title = "Couldn't reach the server.", body = "B.") }
        }
        composeTestRule.onNodeWithText("Couldn't reach the server.").assertIsDisplayed()
    }

    @Test
    fun mandatorySlots_bodyRenders() {
        composeTestRule.setContent {
            FeedTheme { BigMidPaneState(eyebrow = "ERR", title = "T.", body = "The server may be offline.") }
        }
        composeTestRule.onNodeWithText("The server may be offline.").assertIsDisplayed()
    }

    // ── (b) Optional slots collapse cleanly ───────────────────────────────────

    @Test
    fun optionalSlots_allAbsent_noLayoutBreak() {
        composeTestRule.setContent {
            FeedTheme { BigMidPaneState(eyebrow = "TEST", title = "Title.", body = "Body.") }
        }
        // Mandatory slots render; no optional text present.
        composeTestRule.onNodeWithText("TEST").assertIsDisplayed()
        composeTestRule.onNodeWithText("Title.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Body.").assertIsDisplayed()
    }

    @Test
    fun optionalSlots_primaryRendersWhenProvided() {
        composeTestRule.setContent {
            FeedTheme {
                BigMidPaneState(
                    eyebrow = "ERR", title = "T.", body = "B.",
                    primary = "Try again" to {},
                )
            }
        }
        composeTestRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun optionalSlots_primaryAbsentWhenOmitted() {
        composeTestRule.setContent {
            FeedTheme { BigMidPaneState(eyebrow = "ERR", title = "T.", body = "B.") }
        }
        composeTestRule.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun optionalSlots_secondaryRendersWhenProvided() {
        composeTestRule.setContent {
            FeedTheme {
                BigMidPaneState(
                    eyebrow = "ERR", title = "T.", body = "B.",
                    secondary = "Settings" to {},
                )
            }
        }
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun optionalSlots_hintRendersWhenProvided() {
        composeTestRule.setContent {
            FeedTheme {
                BigMidPaneState(
                    eyebrow = "ERR", title = "T.", body = "B.",
                    hint = "Contact support if this persists.",
                )
            }
        }
        composeTestRule.onNodeWithText("Contact support if this persists.").assertIsDisplayed()
    }

    @Test
    fun optionalSlots_primaryClickFiresCallback() {
        var fired = false
        composeTestRule.setContent {
            FeedTheme {
                BigMidPaneState(
                    eyebrow = "ERR", title = "T.", body = "B.",
                    primary = "Retry" to { fired = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Retry").performClick()
        assertTrue("Primary action callback must fire on click", fired)
    }

    // ── (c) Four happy-path variants ──────────────────────────────────────────

    @Test
    fun happyPath_selectAnArticle_renders() {
        composeTestRule.setContent { FeedTheme { BigMidPaneSelectAnArticle() } }
        composeTestRule.onNodeWithText("SELECT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pick something to read.").assertIsDisplayed()
    }

    @Test
    fun happyPath_nothingHereYet_renders() {
        composeTestRule.setContent { FeedTheme { BigMidPaneNothingHereYet() } }
        composeTestRule.onNodeWithText("EMPTY").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nothing here yet.").assertIsDisplayed()
    }

    @Test
    fun happyPath_caughtUp_renders() {
        composeTestRule.setContent { FeedTheme { BigMidPaneCaughtUp(feedCount = 3, onBrowseAll = {}) } }
        composeTestRule.onNodeWithText("INBOX ZERO").assertIsDisplayed()
        composeTestRule.onNodeWithText("You're caught up.").assertIsDisplayed()
    }

    @Test
    fun happyPath_firstRun_renders() {
        composeTestRule.setContent { FeedTheme { BigMidPaneFirstRun(onPasteUrl = {}, onImportOpml = {}) } }
        composeTestRule.onNodeWithText("WELCOME").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start by adding a feed.").assertIsDisplayed()
    }

    // ── (d) Scroll action presence — ensures verticalScroll modifier is not dropped ──

    @Test
    fun scrollAction_caughtUp_isScrollable() {
        composeTestRule.setContent { FeedTheme { BigMidPaneCaughtUp(feedCount = 1, onBrowseAll = {}) } }
        composeTestRule.onNode(hasScrollAction()).assertExists()
    }

    @Test
    fun scrollAction_firstRun_isScrollable() {
        composeTestRule.setContent { FeedTheme { BigMidPaneFirstRun(onPasteUrl = {}, onImportOpml = {}) } }
        composeTestRule.onNode(hasScrollAction()).assertExists()
    }

    @Test
    fun scrollAction_selectAnArticle_isNotScrollable() {
        composeTestRule.setContent { FeedTheme { BigMidPaneSelectAnArticle() } }
        composeTestRule.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }
}
