package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.FeedSnackbarTestTag
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose Robolectric tests for the Feed screen (Phase 8).
 *
 * These tests exercise pure UI logic — filtering, rendering, and navigation —
 * without a live repository or server.
 *
 * Note on Robolectric + Compose: Google Fonts resolution is not available under
 * Robolectric (no Play Services). The font falls back to the system default, which
 * is fine for structural tests. Tests that assert pixel-level font properties should
 * use instrumented tests instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------

    /** Four articles with varying minutesToRead and isRead values (mixed fixture). */
    private val fixtureArticles = listOf(
        ArticleItem(
            id = "1",
            title = "Short Article",
            description = "A short read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/1",
            feedTitle = "Feed One",
            minutesToRead = 2,
            isRead = false,
                        excerpt = "Short excerpt",
        ),
        ArticleItem(
            id = "2",
            title = "Medium Article A",
            description = "A medium read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/2",
            feedTitle = "Feed One",
            minutesToRead = 9,
            isRead = false,
                        excerpt = "Medium excerpt A",
        ),
        ArticleItem(
            id = "3",
            title = "Medium Article B",
            description = "Another medium read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/3",
            feedTitle = "Feed One",
            minutesToRead = 9,
            isRead = true,
                        excerpt = "Medium excerpt B",
        ),
        ArticleItem(
            id = "4",
            title = "Long Article",
            description = "A long read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/4",
            feedTitle = "Feed One",
            minutesToRead = 14,
            isRead = false,
    
            excerpt = "Long excerpt",
        ),
    )

    // ---------------------------------------------------------------------------
    // Test: tapping a row triggers the navigation callback
    // ---------------------------------------------------------------------------

    /**
     * Verifies that tapping an article row invokes [onArticleClick] with the
     * correct article id and title.
     *
     * Phase 9: onArticleClick now receives (articleId, title) instead of (url, title)
     * so the navigation can route to reader/{articleId}.
     */
    @Test
    fun tappingRowNavigatesToReader() {
        var navigatedId: String? = null
        var navigatedTitle: String? = null

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { id, title ->
                        navigatedId = id
                        navigatedTitle = title
                    },
                    onRefresh = {},
                )
            }
        }

        // Tap the first article row
        composeTestRule.onNodeWithText("Short Article").performClick()

        assertEquals("1", navigatedId)
        assertEquals("Short Article", navigatedTitle)
    }

    // ---------------------------------------------------------------------------
    // Test: feed screen header content
    // ---------------------------------------------------------------------------

    /**
     * Verifies the subtitle with correct unread/total counts.
     */
    @Test
    fun feedScreenShowsSubtitleWithCounts() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        // Subtitle: "3 unread · 4 total" — 3 unread because id=3 is read
        composeTestRule.onNodeWithText("3 unread · 4 total").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // Test: pull-to-refresh error banner
    // ---------------------------------------------------------------------------

    /**
     * When [UiState.Error] is set, the "Last sync failed" error banner appears in the header.
     */
    @Test
    fun errorBannerShownWhenRefreshFails() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    uiState = UiState.Error("Could not refresh — showing cached articles"),
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Last sync failed · ", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    /**
     * Tapping "Retry" in the error banner calls [onRefresh].
     */
    @Test
    fun retryClickInvokesOnRefresh() {
        var refreshInvoked = false

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    uiState = UiState.Error("Could not refresh"),
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = { refreshInvoked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Retry").performClick()
        assertTrue(refreshInvoked)
    }

    /**
     * Swiping down on the article list invokes [onRefresh].
     *
     * NOTE: [PullToRefreshBox] gesture dispatch does not work under Robolectric —
     * the touch event reaches Compose but the nested-scroll / overscroll signal
     * that triggers the indicator is never fired. Covered by the instrumented test
     * in [app/src/androidTest/](../../../../../androidTest/java/eu/monniot/feed/ui/feed/FeedScreenInstrumentedTest.kt).
     */
    @Ignore("PullToRefreshBox swipe requires a real Activity — see FeedScreenInstrumentedTest")
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

    // ---------------------------------------------------------------------------
    // Test: read article absent from Unread tab (ticket #40 / FEED-8)
    // ---------------------------------------------------------------------------

    /**
     * When an article's [isRead] is true, it must not appear in [FeedScreenContent] when
     * [initialFilter] is [ArticleFilter.Unread] (the "Unread" tab). This is the core
     * TODO-list behavior: marking an article read removes it from the Unread view instantly.
     */
    @Test
    fun readArticleAbsentFromUnreadFilter() {
        val readArticle = fixtureArticles[0].copy(isRead = true)
        val items = listOf(readArticle) + fixtureArticles.drop(1)

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = items,
                    isRefreshing = false,
                    density = Density.Regular,
                    initialFilter = ArticleFilter.Unread,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText(readArticle.title).assertCountEquals(0)
    }

    // ---------------------------------------------------------------------------
    // Test: offline snackbar (ticket #54 / ERR-4)
    // ---------------------------------------------------------------------------

    /**
     * When [isOffline] is true, the persistent "Offline — cache only" snackbar
     * must be shown. Compose snackbar renders inside [SnackbarHost] as a regular
     * composable node, so [onNodeWithText] can find it.
     */
    @Test
    fun offlineSnackbarShownWhenIsOfflineTrue() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    isOffline = true,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Offline — cache only").assertIsDisplayed()
    }

    /**
     * When [rateLimitDuration] is non-null, the "Auto-sync paused" snackbar appears
     * with the formatted duration.
     */
    @Test
    fun rateLimitSnackbarShownWhenPaused() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    rateLimitDuration = "10m",
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Auto-sync paused — rate limited for 10m").assertIsDisplayed()
    }

    /**
     * Offline takes priority over rate-limit: when both are true, the offline snackbar shows.
     */
    @Test
    fun offlineSnackbarTakesPriorityOverRateLimit() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    isOffline = true,
                    rateLimitDuration = "10m",
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Offline — cache only").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Auto-sync paused — rate limited for 10m").assertCountEquals(0)
    }

    /**
     * When [serverUnreachable] is true, a persistent "Couldn't reach the server"
     * snackbar with a "Retry" action label must appear.
     */
    @Test
    fun serverUnreachableSnackbarShownWhenTrue() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    serverUnreachable = true,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Couldn't reach the server").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    /**
     * F4: the snackbar host renders [eu.monniot.feed.ui.theme.FeedSnackbar] — not a
     * Material `Snackbar` — so an error scenario must surface a node tagged
     * [FeedSnackbarTestTag]. Guards against regressing back to the Material default.
     */
    @Test
    fun feedSnackbarTagShownForErrorScenario() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    serverUnreachable = true,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(FeedSnackbarTestTag).assertIsDisplayed()
    }

    /**
     * When both [isOffline] and [serverUnreachable] are true, the offline snackbar
     * takes priority (isOffline is checked first in the when block).
     */
    @Test
    fun offlineSnackbarTakesPriorityOverServerUnreachable() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    isOffline = true,
                    serverUnreachable = true,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Offline — cache only").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Couldn't reach the server").assertCountEquals(0)
    }

    /**
     * When [isOffline] is false, no offline snackbar must appear.
     */
    @Test
    fun offlineSnackbarAbsentWhenOnline() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    isOffline = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Offline — cache only").assertCountEquals(0)
    }

    // ---------------------------------------------------------------------------
    // ERR-10: zero feeds → first-run mid-pane
    // ---------------------------------------------------------------------------

    @Test
    fun firstRun_showsWelcomeTitle() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    feedCount = 0,
                    feedsLoaded = true,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onFirstRunPasteUrl = {},
                    onFirstRunImportOpml = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Start by adding a feed.").assertIsDisplayed()
    }

    @Test
    fun firstRun_showsPasteUrlButton() {
        var clicked = false
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    feedCount = 0,
                    feedsLoaded = true,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onFirstRunPasteUrl = { clicked = true },
                    onFirstRunImportOpml = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Paste a URL…").performClick()
        assertTrue(clicked)
    }

    @Test
    fun firstRun_showsImportOpmlButton() {
        var clicked = false
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    feedCount = 0,
                    feedsLoaded = true,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onFirstRunPasteUrl = {},
                    onFirstRunImportOpml = { clicked = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Import OPML…").performClick()
        assertTrue(clicked)
    }

    @Test
    fun firstRun_notShownWhenFeedsExist() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    feedCount = 3,
                    feedsLoaded = true,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onFirstRunPasteUrl = {},
                    onFirstRunImportOpml = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("Start by adding a feed.").assertCountEquals(0)
    }

    /**
     * BUG-13: first-run pane must NOT show before feeds have loaded.
     * feedsLoaded=false means the list is still in flight; the empty list is
     * indistinguishable from "loaded and empty" without this flag.
     */
    @Test
    fun firstRun_notShownBeforeFeedsLoad() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = emptyList(),
                    feedCount = 0,
                    feedsLoaded = false,  // not yet loaded
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onFirstRunPasteUrl = {},
                    onFirstRunImportOpml = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("Start by adding a feed.").assertCountEquals(0)
    }

    // ---------------------------------------------------------------------------
    // ERR-11: feeds exist but zero unread → inbox-zero mid-pane on Unread tab
    // ---------------------------------------------------------------------------

    @Test
    fun inboxZero_showsCaughtUpTitle() {
        val allRead = fixtureArticles.map { it.copy(isRead = true) }
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = allRead,
                    feedCount = 2,
                    isRefreshing = false,
                    density = Density.Regular,
                    initialFilter = ArticleFilter.Unread,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onBrowseAll = {},
                )
            }
        }
        composeTestRule.onNodeWithText("You're caught up.").assertIsDisplayed()
    }

    @Test
    fun inboxZero_showsFeedCountInBody() {
        val allRead = fixtureArticles.map { it.copy(isRead = true) }
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = allRead,
                    feedCount = 5,
                    isRefreshing = false,
                    density = Density.Regular,
                    initialFilter = ArticleFilter.Unread,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onBrowseAll = {},
                )
            }
        }
        composeTestRule.onNodeWithText("No unread articles across 5 feeds.", substring = true).assertIsDisplayed()
    }

    @Test
    fun inboxZero_browseAllButtonFiresCallback() {
        val allRead = fixtureArticles.map { it.copy(isRead = true) }
        var clicked = false
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = allRead,
                    feedCount = 2,
                    isRefreshing = false,
                    density = Density.Regular,
                    initialFilter = ArticleFilter.Unread,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onBrowseAll = { clicked = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Browse all articles").performClick()
        assertTrue(clicked)
    }

    @Test
    fun inboxZero_notShownOnAllArticlesFilter() {
        val allRead = fixtureArticles.map { it.copy(isRead = true) }
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = allRead,
                    feedCount = 2,
                    isRefreshing = false,
                    density = Density.Regular,
                    initialFilter = ArticleFilter.All,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onBrowseAll = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("You're caught up.").assertCountEquals(0)
    }

    /**
     * Verifies that the tab bar (bottom navigation) remains visible across a
     * tab switch by testing the [MainTabShell] composable.
     *
     * NOTE: Testing full navigation with [MainTabShell] under Robolectric is
     * limited because [NavHost] requires Compose-navigation infrastructure that
     * is complex to set up without a real Activity context. This test is
     * documented as an instrumented-test candidate.
     *
     * TODO(phase-8): Move to app/src/androidTest/ for full navigation fidelity.
     */
    @Test
    @Ignore("TODO(phase-8): NavHost under Robolectric requires Activity context — move to androidTest/")
    fun tabBarPersistsAcrossTabSwitch() {
        // Documented limitation: requires Activity-level NavHost.
        // The tappingRowNavigatesToReader test above
        // provide core behavioral coverage for Phase 8.
    }
}
