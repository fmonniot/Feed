package eu.monniot.feed.ui.theme

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
class FeedSnackbarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Tone — each tone renders the message ─────────────────────────────────

    @Test
    fun snackbar_info_rendersMessage() {
        composeTestRule.setContent {
            FeedTheme { FeedSnackbar(FeedTone.Info, "Sync complete") }
        }
        composeTestRule.onNodeWithText("Sync complete").assertIsDisplayed()
    }

    @Test
    fun snackbar_warn_rendersMessage() {
        composeTestRule.setContent {
            FeedTheme { FeedSnackbar(FeedTone.Warn, "Working offline") }
        }
        composeTestRule.onNodeWithText("Working offline").assertIsDisplayed()
    }

    @Test
    fun snackbar_err_rendersMessage() {
        composeTestRule.setContent {
            FeedTheme { FeedSnackbar(FeedTone.Err, "Sync failed") }
        }
        composeTestRule.onNodeWithText("Sync failed").assertIsDisplayed()
    }

    // ── Action ────────────────────────────────────────────────────────────────

    @Test
    fun snackbar_withAction_rendersActionLabel() {
        composeTestRule.setContent {
            FeedTheme { FeedSnackbar(FeedTone.Err, "Sync failed", action = "Retry" to {}) }
        }
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun snackbar_withAction_clickFiresCallback() {
        var fired = false
        composeTestRule.setContent {
            FeedTheme {
                FeedSnackbar(FeedTone.Err, "Sync failed", action = "Retry" to { fired = true })
            }
        }
        composeTestRule.onNodeWithText("Retry").performClick()
        assertTrue("Action callback must fire when Retry is tapped", fired)
    }

    @Test
    fun snackbar_withoutAction_noActionLabel() {
        composeTestRule.setContent {
            FeedTheme { FeedSnackbar(FeedTone.Info, "Sync complete") }
        }
        // The only text node present should be the message — no stray action label.
        composeTestRule.onNodeWithText("Sync complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }
}
