package eu.monniot.feed.ui.theme

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedbackComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── TonePill ──────────────────────────────────────────────────────────────

    @Test
    fun tonePill_info_rendersInfoLabel() {
        composeTestRule.setContent { FeedTheme { TonePill(FeedTone.Info) } }
        composeTestRule.onNodeWithText("INFO").assertIsDisplayed()
    }

    @Test
    fun tonePill_warn_rendersWarnLabel() {
        composeTestRule.setContent { FeedTheme { TonePill(FeedTone.Warn) } }
        composeTestRule.onNodeWithText("WARN").assertIsDisplayed()
    }

    @Test
    fun tonePill_err_rendersErrLabel() {
        composeTestRule.setContent { FeedTheme { TonePill(FeedTone.Err) } }
        composeTestRule.onNodeWithText("ERR").assertIsDisplayed()
    }

    // ── InlineFormError ───────────────────────────────────────────────────────

    @Test
    fun inlineFormError_info_rendersLabelAndMessage() {
        composeTestRule.setContent {
            FeedTheme { InlineFormError(FeedTone.Info, "Info form error") }
        }
        composeTestRule.onNodeWithText("INFO").assertIsDisplayed()
        composeTestRule.onNodeWithText("Info form error").assertIsDisplayed()
    }

    @Test
    fun inlineFormError_warn_rendersLabelAndMessage() {
        composeTestRule.setContent {
            FeedTheme { InlineFormError(FeedTone.Warn, "Warn form error") }
        }
        composeTestRule.onNodeWithText("WARN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Warn form error").assertIsDisplayed()
    }

    @Test
    fun inlineFormError_err_rendersLabelAndMessage() {
        composeTestRule.setContent {
            FeedTheme { InlineFormError(FeedTone.Err, "Err form error") }
        }
        composeTestRule.onNodeWithText("ERR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Err form error").assertIsDisplayed()
    }

    // ── InlineReaderNote ──────────────────────────────────────────────────────

    @Test
    fun inlineReaderNote_info_rendersLabelAndMessage() {
        composeTestRule.setContent {
            FeedTheme { InlineReaderNote(FeedTone.Info, "Info reader note") }
        }
        composeTestRule.onNodeWithText("INFO").assertIsDisplayed()
        composeTestRule.onNodeWithText("Info reader note").assertIsDisplayed()
    }

    @Test
    fun inlineReaderNote_warn_rendersLabelAndMessage() {
        composeTestRule.setContent {
            FeedTheme { InlineReaderNote(FeedTone.Warn, "Warn reader note") }
        }
        composeTestRule.onNodeWithText("WARN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Warn reader note").assertIsDisplayed()
    }

    @Test
    fun inlineReaderNote_err_rendersLabelAndMessage() {
        composeTestRule.setContent {
            FeedTheme { InlineReaderNote(FeedTone.Err, "Err reader note") }
        }
        composeTestRule.onNodeWithText("ERR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Err reader note").assertIsDisplayed()
    }
}
