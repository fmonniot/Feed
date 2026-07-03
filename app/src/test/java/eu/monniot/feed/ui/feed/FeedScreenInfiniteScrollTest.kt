package eu.monniot.feed.ui.feed

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ticket #113: replaces the #108 manual "Load more" [androidx.compose.material3.TextButton]
 * with automatic infinite scroll driven by [androidx.compose.foundation.lazy.LazyListState].
 *
 * [FeedScreenContent] fires `onLoadMore` once the last *visible* item comes within
 * [eu.monniot.feed.ui.feed.LOAD_MORE_THRESHOLD_ITEMS] (private; mirrored here as a
 * comment, not referenced directly) of the end of the currently loaded window — see
 * the `LaunchedEffect(listState, filteredItems.size, hasMore)` block in FeedScreen.kt.
 *
 * These tests drive the real [FeedScreenContent] entry point (not a reimplementation),
 * scrolling a real [androidx.compose.foundation.lazy.LazyColumn] under Robolectric via
 * `performScrollToIndex`/`performScrollToNode`. The list itself has no dedicated test
 * tag — [eu.monniot.feed.ui.feed.ScrollIndicatorTestTag] already occupies the single
 * semantics-tag slot on that node (see `lazyColumnScrollbar` in ScrollIndicator.kt) —
 * so it's located via `onNode(hasScrollAction())`, the same pattern
 * [eu.monniot.feed.ui.settings.SettingsScreenTest] uses for its scrollable root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedScreenInfiniteScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeArticle(id: String, isRead: Boolean = false) = ArticleItem(
        id = id,
        title = "Article $id",
        description = "",
        pubDate = "Mon, 1 Jan 2024",
        source = "feed1",
        url = "https://example.com/$id",
        feedTitle = "Feed One",
        isRead = isRead,
        excerpt = "Excerpt $id",
    )

    // -------------------------------------------------------------------
    // Initial render: no load-more indicator when hasMore is false
    // -------------------------------------------------------------------

    @Test
    fun initialRenderShowsNoLoadingIndicatorWhenHasMoreIsFalse() {
        val articles = (1..10).map { makeArticle("$it") }

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articles,
                    isRefreshing = false,
                    density = Density.Regular,
                    hasMore = false,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = { throw AssertionError("onLoadMore must not be invoked when hasMore is false") },
                )
            }
        }

        composeTestRule.onNodeWithTag("load_more_indicator").assertDoesNotExist()
    }

    // -------------------------------------------------------------------
    // Scroll-triggered loadMore(): fires once the last visible item nears
    // the end of the loaded window.
    // -------------------------------------------------------------------

    @Test
    fun scrollingNearEndOfListTriggersLoadMoreWhenHasMoreIsTrue() {
        // 30 articles loaded, more exist upstream (hasMore = true).
        val articles = (1..30).map { makeArticle("$it") }
        var loadMoreCalls = 0

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articles,
                    isRefreshing = false,
                    density = Density.Regular,
                    hasMore = true,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }

        // Scroll to the last article — well within the threshold of the end.
        // The loading indicator (index 30, right after the 30 article rows)
        // must be reachable, confirming it's present while hasMore is true.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("load_more_indicator"))
        composeTestRule.onNodeWithTag("load_more_indicator").assertExists()
        composeTestRule.waitForIdle()

        assertTrue("Scrolling near the end must trigger onLoadMore at least once", loadMoreCalls >= 1)
    }

    @Test
    fun scrollingNearEndDoesNotTriggerLoadMoreWhenHasMoreIsFalse() {
        // Fewer than DEFAULT_PAGE_SIZE — nothing more to load.
        val articles = (1..10).map { makeArticle("$it") }
        var loadMoreCalls = 0

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articles,
                    isRefreshing = false,
                    density = Density.Regular,
                    hasMore = false,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(9)
        composeTestRule.waitForIdle()

        assertEquals("hasMore=false must never trigger onLoadMore", 0, loadMoreCalls)
        composeTestRule.onNodeWithTag("load_more_indicator").assertDoesNotExist()
    }

    // -------------------------------------------------------------------
    // Fetch-in-flight guard: repeated scroll/recomposition passes while
    // hasMore stays true (fetch not yet resolved) must not double-fire.
    // -------------------------------------------------------------------

    @Test
    fun repeatedScrollEventsWhileFetchInFlightDoNotDoubleFireLoadMore() {
        val articles = (1..30).map { makeArticle("$it") }
        var loadMoreCalls = 0

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articles,
                    isRefreshing = false,
                    density = Density.Regular,
                    // hasMore stays true across recompositions below, simulating a
                    // page fetch that hasn't resolved yet (articleItems/hasMore only
                    // change once the ViewModel's round-trip completes).
                    hasMore = true,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }

        // Scroll to the end multiple times without the backing data changing —
        // the local isLoadingMore guard must prevent repeated firing.
        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(29)
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(15)
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(29)
        composeTestRule.waitForIdle()

        assertEquals("The fetch-in-flight guard must prevent repeated loadMore() calls", 1, loadMoreCalls)
    }

    // -------------------------------------------------------------------
    // PR #150 review: on the Unread tab a page can land containing only
    // *read* articles — the raw articleItems window grows but the filtered
    // row count doesn't, and hasMore stays true. The fetch-in-flight guard
    // (and the scroll effect) must key on the raw articleItems.size so they
    // re-arm in that case; keyed on filteredItems.size they never reset and
    // infinite scroll is dead for the rest of the session.
    // -------------------------------------------------------------------

    @Test
    fun unreadTabKeepsLoadingWhenPageLandsWithOnlyReadArticles() {
        val unreadArticles = (1..30).map { makeArticle("$it") }
        val articlesState = mutableStateOf(unreadArticles)
        var loadMoreCalls = 0

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articlesState.value,
                    isRefreshing = false,
                    density = Density.Regular,
                    initialFilter = ArticleFilter.Unread,
                    hasMore = true,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }

        // Scroll near the end of the 30 filtered (unread) rows — first fetch fires.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("load_more_indicator"))
        composeTestRule.waitForIdle()
        assertEquals("First scroll near the end must fire onLoadMore once", 1, loadMoreCalls)

        // The page lands with 50 read articles: the raw window grows 30 → 80,
        // the filtered (unread) row count stays 30, and hasMore stays true.
        // Pre-fix, no LaunchedEffect key changed here, so isLoadingMore stayed
        // true and no amount of further scrolling could ever load again.
        articlesState.value = unreadArticles + (31..80).map { makeArticle("$it", isRead = true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("load_more_indicator"))
        composeTestRule.waitForIdle()

        assertTrue(
            "A page landing with only read articles must re-arm the guard so scrolling keeps loading",
            loadMoreCalls >= 2,
        )
    }

    // -------------------------------------------------------------------
    // PR #150 review: short-list behavior. A filtered list with fewer rows
    // than LOAD_MORE_THRESHOLD_ITEMS can never produce a scroll signal (it
    // doesn't overflow the viewport), so once it's laid out the next page
    // auto-loads to fill the viewport — intended, and pinned here. What must
    // NOT happen is the predicate passing on the pre-layout sentinel
    // (lastVisibleIndex = -1) before anything is measured; the
    // `lastVisibleIndex >= 0` guard covers that, and this test pins that the
    // fetch-in-flight guard still caps the auto-fill at one call while the
    // data doesn't change.
    // -------------------------------------------------------------------

    @Test
    fun shortListWithMorePagesAutoLoadsToFillViewport() {
        // 3 unread rows visible, but hasMore = true (e.g. Unread tab with a
        // mostly-read backlog: 50+ articles loaded, 3 unread).
        val articles = (1..3).map { makeArticle("$it") }
        var loadMoreCalls = 0

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articles,
                    isRefreshing = false,
                    density = Density.Regular,
                    hasMore = true,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }

        composeTestRule.waitForIdle()

        // Auto-fill: exactly one fetch fires after layout, with no scroll
        // gesture. The in-flight guard must stop it re-firing on subsequent
        // recomposition passes while the data hasn't changed.
        assertEquals(
            "A short list with more pages must auto-load exactly once to fill the viewport",
            1,
            loadMoreCalls,
        )
    }

    // -------------------------------------------------------------------
    // Appended content: once a new page lands (hasMore flips to false /
    // articleItems grows), the indicator disappears and stops firing again.
    // -------------------------------------------------------------------

    @Test
    fun loadingIndicatorDisappearsAndStopsFiringOnceHasMoreBecomesFalse() {
        val articles = (1..10).map { makeArticle("$it") }
        var loadMoreCalls = 0

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = articles,
                    isRefreshing = false,
                    density = Density.Regular,
                    hasMore = false,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("load_more_indicator").assertDoesNotExist()

        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(9)
        composeTestRule.waitForIdle()

        assertEquals(0, loadMoreCalls)
        composeTestRule.onNodeWithTag("load_more_indicator").assertDoesNotExist()
    }
}
