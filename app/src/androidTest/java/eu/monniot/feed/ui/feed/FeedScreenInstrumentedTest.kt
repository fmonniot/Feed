package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit4.runners.AndroidJUnit4
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose tests for FeedScreen that require a real Activity context.
 *
 * Companion to [FeedScreenTest] (Robolectric). Run on a device or emulator:
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FeedScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fixtureArticles = listOf(
        ArticleItem(
            id = "1", title = "Short Article", description = "", pubDate = "Mon, 1 Jan 2024",
            source = "feed1", url = "https://example.com/1", feedTitle = "Feed One",
            minutesToRead = 2, isRead = false, excerpt = "Short excerpt",
        ),
        ArticleItem(
            id = "2", title = "Long Article", description = "", pubDate = "Mon, 1 Jan 2024",
            source = "feed1", url = "https://example.com/2", feedTitle = "Feed One",
            minutesToRead = 14, isRead = false, excerpt = "Long excerpt",
        ),
    )

    /**
     * Swiping down on the article list invokes [onRefresh].
     *
     * This is the instrumented counterpart of the Robolectric-ignored
     * [FeedScreenTest.pullToRefreshCallsOnRefresh].
     */
    @Test
    fun pullToRefreshCallsOnRefresh() {
        var refreshInvoked = false

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    uiState = UiState.Idle,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = { refreshInvoked = true },
                )
            }
        }

        composeTestRule.onRoot().performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        assertTrue(refreshInvoked)
    }
}
