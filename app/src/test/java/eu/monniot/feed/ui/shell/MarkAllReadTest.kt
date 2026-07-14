package eu.monniot.feed.ui.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 *  2. The confirmation dialog's structure/wording, rendered directly via the
 *     production [MarkAllReadConfirmDialog] (internal, like
 *     [shouldConfirmMarkAllAsRead], for exactly this reason) instead of a
 *     hand-copied stand-in that could drift from the shipped composable.
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
    // allTabSubtitle — #108 regression pin for the All-tab header wiring
    // ---------------------------------------------------------------------------

    @Test
    fun allTabSubtitle_showsUnreadAndTotal() {
        // #108: total must be the aggregate count, distinct from the unread
        // count — the two are always both present in the All-tab subtitle.
        assertEquals("3 unread · 120 total", allTabSubtitle(unreadCount = 3, totalCount = 120))
    }

    @Test
    fun allTabSubtitle_totalIndependentOfUnread() {
        // When everything is read, unread is 0 but the full total still shows —
        // guards against a rebind that would collapse total onto the page window
        // or the unread count (the exact shape #108 regressed).
        assertEquals("0 unread · 120 total", allTabSubtitle(unreadCount = 0, totalCount = 120))
    }

    // ---------------------------------------------------------------------------
    // feedsTabSubtitle — #124 Feeds-tab header subtitle ("{N} subscriptions ·
    // {M} categories")
    // ---------------------------------------------------------------------------

    @Test
    fun feedsTabSubtitle_pluralCounts() {
        assertEquals("5 subscriptions · 3 categories", feedsTabSubtitle(feedCount = 5, categoryCount = 3))
    }

    @Test
    fun feedsTabSubtitle_singularCounts() {
        // Both nouns singularize independently at count == 1.
        assertEquals("1 subscription · 1 category", feedsTabSubtitle(feedCount = 1, categoryCount = 1))
    }

    @Test
    fun feedsTabSubtitle_zeroCounts() {
        // Zero is plural ("0 subscriptions · 0 categories"), matching "3 unread"
        // wording conventions elsewhere (0 is never treated as singular).
        assertEquals("0 subscriptions · 0 categories", feedsTabSubtitle(feedCount = 0, categoryCount = 0))
    }

    // ---------------------------------------------------------------------------
    // Confirmation dialog rendering + wiring
    // ---------------------------------------------------------------------------

    @Test
    fun dialog_showsUnreadCountInMessage() {
        composeTestRule.setContent {
            FeedTheme {
                MarkAllReadConfirmDialog(unreadCount = 120, onConfirm = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("Mark all 120 unread articles as read? This cannot be undone.")
            .assertIsDisplayed()
    }

    // #135: rebuilt on the shared FeedBottomSheet shell (was a Material3
    // AlertDialog) — pin its own tags, mirroring BUG-60's changeUrl coverage.
    @Test
    fun dialog_rendersOnSharedBottomSheetShell() {
        composeTestRule.setContent {
            FeedTheme {
                MarkAllReadConfirmDialog(unreadCount = 120, onConfirm = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithTag("sheet_mark_all_read").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark All as Read").assertIsDisplayed()
    }

    @Test
    fun dialog_confirmInvokesCallback() {
        var confirmed = false
        composeTestRule.setContent {
            FeedTheme {
                MarkAllReadConfirmDialog(
                    unreadCount = 75,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("mark_all_read_confirm").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun dialog_cancelInvokesDismissWithoutConfirming() {
        var confirmed = false
        var dismissed = false
        composeTestRule.setContent {
            FeedTheme {
                MarkAllReadConfirmDialog(
                    unreadCount = 75,
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("mark_all_read_cancel").performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
    }
}
