package eu.monniot.feed.ui.theme

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModalInterruptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setModal(
        visible: Boolean = true,
        tone: FeedTone = FeedTone.Warn,
        eyebrow: String = "WARN · SESSION EXPIRED",
        title: String = "Your session has expired.",
        body: String = "Sign in again to continue.",
        panelStrip: String? = null,
        primary: Pair<String, () -> Unit> = "Sign in" to {},
        secondary: Pair<String, () -> Unit>? = null,
        onDismissRequest: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            FeedTheme {
                ModalInterrupt(
                    visible = visible,
                    tone = tone,
                    eyebrow = eyebrow,
                    title = title,
                    body = body,
                    panelStrip = panelStrip,
                    primary = primary,
                    secondary = secondary,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }

    // ── Scrim / visibility ────────────────────────────────────────────────────

    @Test
    fun scrim_dialogRendersWhenVisible() {
        // Dialog renders (implying the system-provided scrim blocks input behind it).
        setModal(visible = true)
        composeTestRule.onNodeWithText("Your session has expired.").assertIsDisplayed()
    }

    @Test
    fun scrim_dialogAbsentWhenNotVisible() {
        setModal(visible = false)
        composeTestRule.onAllNodesWithText("Your session has expired.").assertCountEquals(0)
    }

    // ── Mandatory slots ───────────────────────────────────────────────────────

    @Test
    fun mandatorySlots_eyebrowTitleBodyRender() {
        setModal(
            eyebrow = "WARN · SESSION EXPIRED",
            title = "Your session has expired.",
            body = "Sign in again to continue.",
        )
        composeTestRule.onNodeWithText("WARN · SESSION EXPIRED").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your session has expired.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign in again to continue.").assertIsDisplayed()
    }

    // ── Primary action callback ───────────────────────────────────────────────

    @Test
    fun primary_callbackFiresOnClick() {
        var fired = false
        setModal(primary = "Sign in" to { fired = true })
        composeTestRule.onNodeWithText("Sign in").performClick()
        assertTrue("Primary callback must fire on click", fired)
    }

    // ── Optional panel strip ──────────────────────────────────────────────────

    @Test
    fun panelStrip_rendersContentVerbatim() {
        setModal(panelStrip = "admin@feed.app")
        composeTestRule.onNodeWithText("admin@feed.app").assertIsDisplayed()
    }

    @Test
    fun panelStrip_absentWhenNull() {
        setModal(panelStrip = null)
        composeTestRule.onAllNodesWithText("admin@feed.app").assertCountEquals(0)
    }

    // ── Optional secondary ────────────────────────────────────────────────────

    @Test
    fun secondary_rendersWhenProvided() {
        setModal(secondary = "Cancel" to {})
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun secondary_callbackFiresOnClick() {
        var fired = false
        setModal(secondary = "Cancel" to { fired = true })
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue("Secondary callback must fire on click", fired)
    }

    @Test
    fun secondary_absentWhenNull() {
        setModal(secondary = null)
        composeTestRule.onAllNodesWithText("Cancel").assertCountEquals(0)
    }
}
