package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the [ArticleRow] mark-as-read affordance (ticket #40 / FEED-8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArticleRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val unreadArticle = ArticleItem(
        id = "row-test-1",
        title = "Unread Article",
        description = "<p>Content.</p>",
        pubDate = "1h ago",
        source = "feed",
        url = "https://example.com/1",
        feedTitle = "Feed One",
        isRead = false,
        minutesToRead = 4,
        excerpt = "Short excerpt.",
    )

    @Test
    fun markReadButtonPresentWhenUnread() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = {},
                    onMarkAsRead = {},
                )
            }
        }
        composeTestRule.onNodeWithText("✓").assertExists()
    }

    @Test
    fun markReadButtonAbsentWhenRead() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle.copy(isRead = true),
                    density = Density.Regular,
                    onClick = {},
                    onMarkAsRead = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("✓").assertCountEquals(0)
    }

    @Test
    fun clickingMarkReadFiresCallbackNotRowClick() {
        var markReadCalled = false
        var rowClickCalled = false

        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = { rowClickCalled = true },
                    onMarkAsRead = { markReadCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithText("✓").performClick()

        assertTrue("onMarkAsRead must fire when ✓ is tapped", markReadCalled)
        assertFalse("onClick (row navigation) must NOT fire when ✓ is tapped", rowClickCalled)
    }

    @Test
    fun comfyDensityShowsThumbnailAndExcerpt() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(article = unreadArticle, density = Density.Comfy, onClick = {})
            }
        }
        composeTestRule.onNodeWithContentDescription("Article thumbnail").assertExists()
        composeTestRule.onNodeWithText(unreadArticle.excerpt).assertExists()
    }

    @Test
    fun compactDensityHidesThumbnailAndExcerpt() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(article = unreadArticle, density = Density.Compact, onClick = {})
            }
        }
        composeTestRule.onAllNodesWithContentDescription("Article thumbnail").assertCountEquals(0)
        composeTestRule.onAllNodesWithText(unreadArticle.excerpt).assertCountEquals(0)
    }

    @Test
    fun clickingRowBodyFiresRowClickNotMarkRead() {
        var markReadCalled = false
        var rowClickCalled = false

        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = { rowClickCalled = true },
                    onMarkAsRead = { markReadCalled = true },
                )
            }
        }

        // Tap the article title (not the ✓ button)
        composeTestRule.onNodeWithText("Unread Article").performClick()

        assertTrue("onClick must fire when the article title is tapped", rowClickCalled)
        assertFalse("onMarkAsRead must NOT fire when the title is tapped", markReadCalled)
    }

    // ---------------------------------------------------------------------------
    // Ticket #9: selection mode
    // ---------------------------------------------------------------------------

    @Test
    fun selectionMode_showsCheckbox() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = {},
                    selectionMode = true,
                    isSelected = false,
                    onToggleSelect = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("article_select_checkbox_${unreadArticle.id}").assertExists()
    }

    @Test
    fun selectionMode_checkboxReflectsSelectedState() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = {},
                    selectionMode = true,
                    isSelected = true,
                    onToggleSelect = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("article_select_checkbox_${unreadArticle.id}").assertIsOn()
    }

    @Test
    fun selectionMode_checkboxUncheckedWhenNotSelected() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = {},
                    selectionMode = true,
                    isSelected = false,
                    onToggleSelect = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("article_select_checkbox_${unreadArticle.id}").assertIsOff()
    }

    @Test
    fun noCheckboxWhenNotInSelectionMode() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = {},
                    selectionMode = false,
                )
            }
        }
        composeTestRule.onAllNodesWithTag("article_select_checkbox_${unreadArticle.id}").assertCountEquals(0)
    }

    @Test
    fun selectionMode_tappingRowTogglesSelectionNotNavigation() {
        var rowClickCalled = false
        var toggleCalled = false

        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = { rowClickCalled = true },
                    selectionMode = true,
                    isSelected = false,
                    onToggleSelect = { toggleCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Unread Article").performClick()

        assertTrue("tapping a row in selection mode must toggle selection", toggleCalled)
        assertFalse("tapping a row in selection mode must NOT navigate", rowClickCalled)
    }

    @Test
    fun markReadCheckHiddenInSelectionMode() {
        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = {},
                    onMarkAsRead = {},
                    selectionMode = true,
                    onToggleSelect = {},
                )
            }
        }
        // The per-row ✓ mark-read button must be hidden while multi-selecting.
        composeTestRule.onAllNodesWithText("✓").assertCountEquals(0)
    }

    @Test
    fun longPress_firesOnLongClick() {
        var longClicked = false

        composeTestRule.setContent {
            FeedTheme {
                ArticleRow(
                    article = unreadArticle,
                    density = Density.Regular,
                    onClick = {},
                    onLongClick = { longClicked = true },
                    onToggleSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Unread Article").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertTrue("long-pressing a row must fire onLongClick", longClicked)
    }
}
