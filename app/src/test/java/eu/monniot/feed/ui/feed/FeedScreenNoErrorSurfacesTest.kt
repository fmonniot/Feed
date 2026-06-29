package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.subs.SubscriptionsScreenContent
import eu.monniot.feed.ui.theme.FeedSnackbarTestTag
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG-23 / #86: Verifies that feed-level parse errors (and other recurring
 * feed-state errors) do NOT produce transient snackbars or toasts on the
 * article list screen. The persistent error badge on the Feeds tab and
 * subscriptions screen is the sole source of error visibility for these
 * conditions.
 *
 * Critical infrastructure errors (offline, rate-limit, server-unreachable)
 * still show snackbars — those are tested in [FeedScreenTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedScreenNoErrorSurfacesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------

    /** Articles from a feed that happens to be in parse_error state on the server. */
    private val articlesFromBrokenFeed = listOf(
        ArticleItem(
            id = "a1",
            title = "Cached article from broken feed",
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

    /** A feed with parse_error status — simulates BUG-23 scenario. */
    private val parseErrorFeed = FeedUiItem(
        id = 42,
        displayTitle = "Broken Feed",
        rawCustomTitle = null,
        url = "https://broken.example.com/feed.xml",
        unreadCount = 1,
        isPaused = false,
        errorCount = 3,
        fetchIntervalMinutes = 60,
        serverFeedStatus = "parse_error",
        severity = "error",
        lastErrorKind = "parse",
        lastHttpStatus = 200,
        consecutiveFailureCount = 3,
        lastAttempt = 1718900000L,
        retriesPaused = false,
    )

    /** A dead feed (HTTP 410 Gone). */
    private val deadFeed = FeedUiItem(
        id = 43,
        displayTitle = "Dead Feed",
        rawCustomTitle = null,
        url = "https://dead.example.com/rss",
        unreadCount = 0,
        isPaused = false,
        errorCount = 14,
        fetchIntervalMinutes = 60,
        serverFeedStatus = "dead",
        severity = "error",
        lastErrorKind = "http_410",
        lastHttpStatus = 410,
        consecutiveFailureCount = 14,
        lastAttempt = 1718800000L,
        first410At = 1717900000L,
        retriesPaused = true,
    )

    /** A healthy feed for contrast. */
    private val healthyFeed = FeedUiItem(
        id = 1,
        displayTitle = "Healthy Feed",
        rawCustomTitle = null,
        url = "https://healthy.example.com/rss",
        unreadCount = 5,
        isPaused = false,
        errorCount = 0,
        fetchIntervalMinutes = 30,
    )

    // ---------------------------------------------------------------------------
    // FeedScreen: no snackbar for parse-error feeds (BUG-23)
    // ---------------------------------------------------------------------------

    @Test
    fun noParseErrorSnackbar_withArticles() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articlesFromBrokenFeed,
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

    /**
     * BUG-23 core scenario: after a "successful" sync that leaves parse-error
     * feeds in place, the FeedScreen must show NO snackbar. Only infrastructure
     * errors (offline, rate-limit, server-unreachable) drive the snackbar.
     */
    @Test
    fun noSnackbar_afterSyncWithParseErrorFeeds() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articlesFromBrokenFeed,
                    isRefreshing = false,
                    // Sync succeeded (server handled the parse error internally)
                    isOffline = false,
                    serverUnreachable = false,
                    rateLimitDuration = null,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        // No FeedSnackbar should be rendered at all
        composeTestRule.onAllNodesWithTag(FeedSnackbarTestTag).assertCountEquals(0)
        // No error text related to parsing
        composeTestRule.onNodeWithText("parse", substring = true, ignoreCase = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("couldn't be parsed", substring = true).assertDoesNotExist()
    }

    /**
     * BUG-23: during an active refresh (isRefreshing = true), no parse-error
     * snackbar should appear either.
     */
    @Test
    fun noSnackbar_duringRefreshWithParseErrorFeeds() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articlesFromBrokenFeed,
                    isRefreshing = true,
                    isOffline = false,
                    serverUnreachable = false,
                    rateLimitDuration = null,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(FeedSnackbarTestTag).assertCountEquals(0)
    }

    // ---------------------------------------------------------------------------
    // SubscriptionsScreen: error badge IS visible for parse-error feeds (BUG-23)
    // ---------------------------------------------------------------------------

    /**
     * BUG-23 complementary check: the Subscriptions screen DOES show the error
     * badge and summary banner for parse-error feeds. This is the persistent
     * error surface that replaces the removed snackbar.
     */
    @Test
    fun subscriptions_showsErrorBadgeForParseErrorFeed() {
        composeTestRule.setContent {
            FeedTheme {
                SubscriptionsScreenContent(
                    feeds = listOf(parseErrorFeed, healthyFeed),
                    categories = emptyList(),
                    isLoading = false,
                    errorMessage = null,
                    addFeedError = null,
                    addFeedLoading = false,
                    onAddFeed = { _, _ -> },
                    onRename = { _, _ -> },
                    onSetCategory = { _, _ -> },
                    onSetFeedInterval = { _, _ -> },
                    onTogglePaused = { _, _ -> },
                    onDelete = {},
                    onErrorDismiss = {},
                    onAddFeedErrorDismiss = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        // Error summary banner is visible
        composeTestRule.onNodeWithTag("error_summary_banner").assertIsDisplayed()
        // The "PARSE FAIL" badge appears on the broken feed row
        composeTestRule.onNodeWithText("PARSE FAIL").assertIsDisplayed()
        // The broken feed row is clickable (has its accordion tag)
        composeTestRule.onNodeWithTag("broken_feed_row_42").assertExists()
    }

    /**
     * BUG-23: with multiple broken feeds (parse_error + dead), the summary
     * banner shows the combined count and no snackbar fires.
     */
    @Test
    fun subscriptions_showsSummaryForMultipleBrokenFeeds_noSnackbar() {
        composeTestRule.setContent {
            FeedTheme {
                SubscriptionsScreenContent(
                    feeds = listOf(parseErrorFeed, deadFeed, healthyFeed),
                    categories = emptyList(),
                    isLoading = false,
                    // No errorMessage = no snackbar fires
                    errorMessage = null,
                    addFeedError = null,
                    addFeedLoading = false,
                    onAddFeed = { _, _ -> },
                    onRename = { _, _ -> },
                    onSetCategory = { _, _ -> },
                    onSetFeedInterval = { _, _ -> },
                    onTogglePaused = { _, _ -> },
                    onDelete = {},
                    onErrorDismiss = {},
                    onAddFeedErrorDismiss = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        // Summary banner visible with count
        composeTestRule.onNodeWithTag("error_summary_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_count_chip").assertIsDisplayed()
        // Both broken feed rows exist
        composeTestRule.onNodeWithTag("broken_feed_row_42").assertExists()
        composeTestRule.onNodeWithTag("broken_feed_row_43").assertExists()
        // The healthy feed shows normally (no broken row tag)
        composeTestRule.onNodeWithTag("feed_name_1").assertExists()
    }
}
