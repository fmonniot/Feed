package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
