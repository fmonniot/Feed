package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeArticle
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #108: Verifies the pagination contract — loadMore() expands the article
 * window, hasMore reflects whether more articles exist, and the unread badge
 * always shows the full aggregate count regardless of the window size.
 */
class FeedViewModelPaginationTest {

    private fun makeVm(repo: FakeFeedRepository, scope: CoroutineScope): FeedViewModel {
        val settings: Settings = InMemorySettings()
        return FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(InMemorySettings()),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
    }

    /**
     * Subscribes to [FeedViewModel.unreadCount], lets the upstream settle, then
     * reads the current value.
     */
    private suspend fun TestScope.awaitBadge(vm: FeedViewModel): Int {
        val job = launch { vm.unreadCount.collect {} }
        testScheduler.advanceUntilIdle()
        val badge = vm.unreadCount.value
        job.cancel()
        return badge
    }

    /**
     * Subscribes to [FeedViewModel.articleItems] and [FeedViewModel.hasMore],
     * advances the scheduler, and returns the current article list.
     */
    private suspend fun TestScope.awaitArticles(vm: FeedViewModel): List<ArticleItem> {
        val articlesJob = launch { vm.articleItems.collect {} }
        val hasMoreJob = launch { vm.hasMore.collect {} }
        testScheduler.advanceUntilIdle()
        val items = vm.articleItems.value ?: emptyList()
        articlesJob.cancel()
        hasMoreJob.cancel()
        return items
    }

    // ── Badge shows full count even when list is windowed ─────────────────

    /**
     * With 100 unread articles (> DEFAULT_PAGE_SIZE=50), the badge must show
     * the true global count of 100, not the windowed list size.
     */
    @Test
    fun badge_shows_full_unread_count_not_page_window() = runTest {
        val totalUnread = 100
        val articles = (1..totalUnread).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        val items = awaitArticles(vm)
        val badge = awaitBadge(vm)

        assertEquals(
            FeedViewModel.DEFAULT_PAGE_SIZE, items.size,
            "list must be capped at DEFAULT_PAGE_SIZE on the first page"
        )
        assertEquals(
            totalUnread, badge,
            "badge must show the full unread count ($totalUnread), not the window size (${items.size})"
        )
        vm.close()
    }

    // ── loadMore() expands the window ────────────────────────────────────

    /**
     * Calling loadMore() when hasMore is true expands the article list by one
     * page. With 75 articles, the first page shows 50 and the second shows 25.
     */
    @Test
    fun loadMore_appends_next_page() = runTest {
        val totalArticles = 75
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // First page
        val firstPage = awaitArticles(vm)
        assertEquals(FeedViewModel.DEFAULT_PAGE_SIZE, firstPage.size, "first page must show DEFAULT_PAGE_SIZE articles")
        assertTrue(vm.hasMore.value, "hasMore must be true when more articles exist")

        // Load second page
        vm.loadMore()
        testScheduler.advanceUntilIdle()

        val secondPage = awaitArticles(vm)
        assertEquals(totalArticles, secondPage.size, "after loadMore, all 75 articles must be visible")
        assertFalse(vm.hasMore.value, "hasMore must be false when all articles are loaded")
        vm.close()
    }

    /**
     * Multiple loadMore() calls each expand the window by DEFAULT_PAGE_SIZE.
     * With 120 articles: page 1 = 50, page 2 = 100, page 3 = 120 (all).
     */
    @Test
    fun loadMore_multiple_pages() = runTest {
        val totalArticles = 120
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Page 1
        val page1 = awaitArticles(vm)
        assertEquals(50, page1.size)
        assertTrue(vm.hasMore.value)

        // Page 2
        vm.loadMore()
        testScheduler.advanceUntilIdle()
        val page2 = awaitArticles(vm)
        assertEquals(100, page2.size)
        assertTrue(vm.hasMore.value, "hasMore must be true — 20 more articles remain")

        // Page 3
        vm.loadMore()
        testScheduler.advanceUntilIdle()
        val page3 = awaitArticles(vm)
        assertEquals(120, page3.size)
        assertFalse(vm.hasMore.value, "hasMore must be false — all articles loaded")

        vm.close()
    }

    /**
     * loadMore() is a no-op when hasMore is false.
     */
    @Test
    fun loadMore_noop_when_no_more() = runTest {
        val articles = (1..30).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        val items = awaitArticles(vm)
        assertEquals(30, items.size, "all articles fit in one page")
        assertFalse(vm.hasMore.value, "hasMore must be false when all articles fit in one page")

        // loadMore should be a no-op
        vm.loadMore()
        testScheduler.advanceUntilIdle()

        val itemsAfter = awaitArticles(vm)
        assertEquals(30, itemsAfter.size, "list must not change after no-op loadMore")
        vm.close()
    }

    // ── hasMore is false when articles fit in one page ────────────────────

    @Test
    fun hasMore_false_when_under_page_size() = runTest {
        val articles = (1..25).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        awaitArticles(vm)
        assertFalse(vm.hasMore.value, "hasMore must be false when article count < DEFAULT_PAGE_SIZE")
        vm.close()
    }

    @Test
    fun hasMore_true_when_exactly_page_size() = runTest {
        // Exactly DEFAULT_PAGE_SIZE articles — there might be more, so hasMore should be true.
        // The store returns exactly `windowSize` items, which is the threshold.
        val articles = (1..FeedViewModel.DEFAULT_PAGE_SIZE).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        awaitArticles(vm)
        assertTrue(vm.hasMore.value, "hasMore must be true when exactly DEFAULT_PAGE_SIZE articles are returned (there may be more)")
        vm.close()
    }

    // ── loadMore() works with no active hasMore/articleItems collector (BUG-48) ──

    /**
     * BUG-48: [FeedViewModel.loadMore] used to gate on `hasMore.value`, a
     * `WhileSubscribed(5000)` `StateFlow` whose upstream `combine()` only runs
     * while at least one collector is attached. Calling `loadMore()` with
     * *nothing* collecting `hasMore` (or `articleItems`) used to silently
     * no-op, no matter how many articles remained, because `.value` stayed
     * pinned at the seeded `false`. This test deliberately collects neither
     * flow before calling `loadMore()` and asserts the window still expands —
     * the fix reads the repository directly instead of through the
     * subscriber-gated `StateFlow`s.
     */
    @Test
    fun loadMore_advances_window_without_any_active_collector() = runTest {
        val totalArticles = 75
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Deliberately do NOT collect hasMore or articleItems anywhere before
        // calling loadMore() — this is the exact scenario BUG-48 broke.
        vm.loadMore()
        testScheduler.advanceUntilIdle()

        // Now subscribe (as the UI eventually would) and confirm the window
        // actually expanded to the second page instead of staying at 50.
        val items = awaitArticles(vm)
        assertEquals(
            totalArticles, items.size,
            "loadMore() must expand the window to all $totalArticles articles even though " +
                "nothing was collecting hasMore/articleItems when it was called"
        )
        vm.close()
    }

    /**
     * A keepArticleId-only filter change mid-flight must NOT discard an
     * in-flight [FeedViewModel.loadMore]. In the unread view, tapping an
     * article recomputes `_currentFilter` as `UnreadOnly(keepArticleId=...)`
     * without resetting `_pageCount` (the pagination model preserves expanded
     * pages across that transition). loadMore()'s in-flight re-check therefore
     * compares pagination *scope* (`samePageScopeAs`) rather than full filter
     * equality, so the legitimate page load survives. With a strict `==`
     * re-check this would silently drop the increment and the window would
     * stay at page 1.
     */
    @Test
    fun loadMore_survives_keepArticleId_only_filter_change() = runTest {
        val totalArticles = 120
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Switch to the unread view so the filter is UnreadOnly.
        vm.selectFeed(feedId = null, showAll = false)
        testScheduler.advanceUntilIdle()

        // Fire loadMore(), then — before its launched coroutine runs — change
        // only the kept article id (as tapping an article mid-scroll would).
        vm.loadMore()
        vm.selectArticle("1")
        testScheduler.advanceUntilIdle()

        val items = awaitArticles(vm)
        assertEquals(
            100, items.size,
            "a keepArticleId-only filter change must not discard the in-flight page load — " +
                "window should advance to page 2 (100), not stay at page 1 (50)"
        )
        vm.close()
    }

    /**
     * Concurrent [FeedViewModel.loadMore] calls collapse into a single page
     * advance. The second call's launched coroutine fails the
     * `_pageCount.value == requestedPageCount` re-check (the first call already
     * committed the increment) and is silently dropped, so two calls advance
     * one page, not two.
     */
    @Test
    fun loadMore_concurrent_calls_advance_single_page() = runTest {
        val totalArticles = 120
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Both calls capture requestedPageCount=1 before either coroutine runs.
        vm.loadMore()
        vm.loadMore()
        testScheduler.advanceUntilIdle()

        val items = awaitArticles(vm)
        assertEquals(
            100, items.size,
            "two concurrent loadMore() calls must advance a single page (window 100), not two (150)"
        )
        vm.close()
    }

    /**
     * A view change mid-flight discards an in-flight [FeedViewModel.loadMore].
     * loadMore() captures the old filter, then `selectFeed` swaps the filter to
     * a different scope before the launched coroutine runs; the
     * `samePageScopeAs` re-check fails and the increment is dropped, leaving the
     * new view at page 1.
     */
    @Test
    fun loadMore_discarded_on_view_change() = runTest {
        val totalArticles = 120
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Article $i").copy(feedId = 1)
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Fire loadMore() against the default (All) view, then switch to a feed
        // view before the launched coroutine commits its increment.
        vm.loadMore()
        vm.selectFeed(1)
        testScheduler.advanceUntilIdle()

        val items = awaitArticles(vm)
        assertEquals(
            FeedViewModel.DEFAULT_PAGE_SIZE, items.size,
            "an in-flight loadMore() must be discarded when the view changes — " +
                "the new feed view stays at page 1 (50)"
        )
        vm.close()
    }

    // ── Pagination resets on filter change ────────────────────────────────

    @Test
    fun selectFeed_resets_pagination() = runTest {
        val totalArticles = 75
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Article $i").copy(feedId = 1)
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Load two pages
        awaitArticles(vm)
        vm.loadMore()
        testScheduler.advanceUntilIdle()
        val expanded = awaitArticles(vm)
        assertEquals(totalArticles, expanded.size, "after loadMore, all articles should be visible")

        // Switch feed filter — pagination should reset to page 1
        vm.selectFeed(1)
        testScheduler.advanceUntilIdle()

        val afterFilter = awaitArticles(vm)
        assertEquals(
            FeedViewModel.DEFAULT_PAGE_SIZE, afterFilter.size,
            "after selectFeed, list must be reset to first page (DEFAULT_PAGE_SIZE)"
        )
        assertTrue(vm.hasMore.value, "hasMore must be true after filter reset with >50 articles")
        vm.close()
    }

    // ── Badge stays correct across loadMore() calls ──────────────────────

    @Test
    fun badge_unchanged_after_loadMore() = runTest {
        val totalUnread = 100
        val articles = (1..totalUnread).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        awaitArticles(vm)
        val badgeBefore = awaitBadge(vm)
        assertEquals(totalUnread, badgeBefore, "badge must show full count before loadMore")

        vm.loadMore()
        testScheduler.advanceUntilIdle()

        awaitArticles(vm)
        val badgeAfter = awaitBadge(vm)
        assertEquals(totalUnread, badgeAfter, "badge must still show full count after loadMore")
        vm.close()
    }
}
