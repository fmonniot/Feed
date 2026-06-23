package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #86: Asserts that the article list no longer shows the per-feed error
 * surfaces removed in the consolidation pass:
 *
 *  - ERR-8 parse-error "Details" snackbar (removed)
 *
 * Parse-failing feeds now show their cached articles without a snackbar;
 * the Feeds tab accordion is the only parse-error surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedScreenNoErrorSurfacesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleArticles = listOf(
        ArticleItem(
            id = "a1",
            title = "Test article",
            description = "desc",
            pubDate = "1h ago",
            source = "test",
            url = "https://example.com/a1",
            feedTitle = "Broken Feed",
            feedId = 42,
            feedHue = 120,
            isRead = false,
            author = "Author",
            minutesToRead = 5,
            excerpt = "Some excerpt text.",
        )
    )

    @Test
    fun noParseErrorSnackbar_withArticles() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = sampleArticles,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        // The old "Details" snackbar must no longer appear
        composeTestRule.onNodeWithText("Details").assertDoesNotExist()
        // The old parse-error message must not appear
        composeTestRule.onNodeWithText("A feed couldn't be parsed", substring = true).assertDoesNotExist()
    }

    @Test
    fun noParseErrorSnackbar_emptyList() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Details").assertDoesNotExist()
        composeTestRule.onNodeWithText("A feed couldn't be parsed", substring = true).assertDoesNotExist()
    }
}
