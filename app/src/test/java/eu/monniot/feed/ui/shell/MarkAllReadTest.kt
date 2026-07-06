package eu.monniot.feed.ui.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.monniot.feed.ui.theme.FeedButton
import eu.monniot.feed.ui.theme.FeedTextButton
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ticket #9's "Mark all as read" confirmation-threshold logic and
 * its dialog.
 *
 * [MainTabShell] itself isn't directly testable under Robolectric (it needs a
 * live [eu.monniot.feed.FeedViewModel] + [androidx.navigation.NavController] —
 * see the note in TabScreenHeaderTest), so this file pins:
 *  1. [shouldConfirmMarkAllAsRead] — the pure threshold decision, unit-tested
 *     directly with no Compose involved.
 *  2. The confirmation dialog's structure/wording, rendered standalone with
 *     the same shape used by MainTabShell's private `MarkAllReadConfirmDialog`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkAllReadTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // shouldConfirmMarkAllAsRead — pure threshold logic
    // ---------------------------------------------------------------------------

    @Test
    fun threshold_isFiftyArticles() {
        assertEquals(50, MARK_ALL_READ_CONFIRM_THRESHOLD)
    }

    @Test
    fun belowThreshold_doesNotRequireConfirmation() {
        assertFalse(shouldConfirmMarkAllAsRead(0))
        assertFalse(shouldConfirmMarkAllAsRead(1))
        assertFalse(shouldConfirmMarkAllAsRead(49))
    }

    @Test
    fun exactlyAtThreshold_doesNotRequireConfirmation() {
        // ">" not ">=" — exactly 50 unread fires directly, matching the
        // ticket's "confirmation if unread count > 50" wording.
        assertFalse(shouldConfirmMarkAllAsRead(50))
    }

    @Test
    fun aboveThreshold_requiresConfirmation() {
        assertTrue(shouldConfirmMarkAllAsRead(51))
        assertTrue(shouldConfirmMarkAllAsRead(500))
    }

    // ---------------------------------------------------------------------------
    // Confirmation dialog rendering + wiring
    // ---------------------------------------------------------------------------

    /** Local stand-in for MainTabShell's private MarkAllReadConfirmDialog, same shape. */
    @androidx.compose.runtime.Composable
    private fun TestMarkAllReadConfirmDialog(
        unreadCount: Int,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Mark All as Read") },
            text = { Text("Mark all $unreadCount unread articles as read? This cannot be undone.") },
            confirmButton = {
                FeedButton(onClick = onConfirm, label = "Mark all read")
            },
            dismissButton = {
                FeedTextButton(onClick = onDismiss, label = "Cancel")
            },
        )
    }

    @Test
    fun dialog_showsUnreadCountInMessage() {
        composeTestRule.setContent {
            FeedTheme {
                TestMarkAllReadConfirmDialog(unreadCount = 120, onConfirm = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("Mark all 120 unread articles as read? This cannot be undone.")
            .assertIsDisplayed()
    }

    @Test
    fun dialog_confirmInvokesCallback() {
        var confirmed = false
        composeTestRule.setContent {
            FeedTheme {
                TestMarkAllReadConfirmDialog(
                    unreadCount = 75,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Mark all read").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun dialog_cancelInvokesDismissWithoutConfirming() {
        var confirmed = false
        var dismissed = false
        composeTestRule.setContent {
            FeedTheme {
                TestMarkAllReadConfirmDialog(
                    unreadCount = 75,
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
    }
}
