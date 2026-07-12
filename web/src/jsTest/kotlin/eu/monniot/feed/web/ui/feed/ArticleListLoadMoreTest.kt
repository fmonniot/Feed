package eu.monniot.feed.web.ui.feed

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.Route
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Inline test doubles ──────────────────────────────────────────────────────
// The web test module cannot access shared/commonTest, so we duplicate the
// minimal fakes needed to construct a FeedViewModel (see
// SidebarGlobalCounterTest.kt / LoginServerUrlIntegrationTest.kt for the same
// pattern).

private class LoadMoreInMemorySettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun hasKey(key: String): Boolean = key in map
    override fun remove(key: String) { map.remove(key) }
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String) = map[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double) = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String) = map[key] as? Double
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float) = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String) = map[key] as? Float
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int) = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String) = map[key] as? Int
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long) = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String) = map[key] as? Long
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
}

/**
 * A [FeedRepository] fake whose [itemsFlow] backs [observePage] (scoped/
 * filtered, like the real stores) as well as [observeUnreadCount] and
 * [observeTotalCount].
 */
private class LoadMoreFakeFeedRepository(
    val itemsFlow: MutableStateFlow<List<ArticleItem>>,
) : FeedRepository {
    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        itemsFlow.map { items ->
            val filtered = when (filter) {
                is ArticleFilter.All -> items
                is ArticleFilter.UnreadOnly -> items.filter {
                    !it.isRead || it.id == filter.keepArticleId?.toString()
                }
                is ArticleFilter.ByFeed -> items.filter { it.feedId == filter.feedId }
            }
            val start = window.first.coerceAtMost(filtered.size)
            val end = (window.last + 1).coerceAtMost(filtered.size)
            filtered.subList(start, end)
        }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
        itemsFlow.map { items ->
            when (filter) {
                is ArticleFilter.All -> items.count { !it.isRead }
                is ArticleFilter.UnreadOnly -> items.count { !it.isRead }
                is ArticleFilter.ByFeed -> items.count { it.feedId == filter.feedId && !it.isRead }
            }
        }

    override fun observeTotalCount(): Flow<Int> = itemsFlow.map { it.size }

    override fun observeCount(filter: ArticleFilter): Flow<Int> =
        itemsFlow.map { items ->
            when (filter) {
                is ArticleFilter.All -> items.size
                is ArticleFilter.UnreadOnly -> items.count { !it.isRead }
                is ArticleFilter.ByFeed -> items.count { it.feedId == filter.feedId }
            }
        }

    override suspend fun refresh() {}
    override suspend fun refreshUpstream(): RefreshResult = RefreshResult.Success(0)
    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult = RefreshResult.Success(0)
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun markAllAsRead() {}
    override suspend fun markFeedAsRead(feedId: Int) {}
    override suspend fun markArticlesAsRead(articleIds: List<Int>) {}
    override suspend fun markArticlesAsUnread(articleIds: List<Int>) {}
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = FeedAddResponse(id = 99, message = "ok")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun createCategory(name: String): Int = 0
    override suspend fun renameCategory(categoryId: Int, newName: String) {}
    override suspend fun deleteCategory(categoryId: Int, reassignTo: Int?) {}
    override suspend fun reorderCategories(orderedCategoryIds: List<Int>) {}
    override suspend fun reorderFeeds(orderedFeedIds: List<Int>) {}
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult = OpmlImportResult(
        total_feeds = 0, imported = 0, already_exists = 0,
        failed = 0, categories_created = 0, feeds = emptyList(),
    )
    override suspend fun getServerVersion(): String = "0.0.0"
    override suspend fun getParseError(feedId: Int): FeedParseError? = null
    override suspend fun clearArticles() {}
    override suspend fun getRetention(): Int? = 90
    override suspend fun setRetention(days: Int?) {}
}

private fun loadMoreMakeArticle(id: String, feedId: Int = 1, isRead: Boolean = false) = ArticleItem(
    id = id,
    title = "Article $id",
    description = "",
    pubDate = "",
    source = "test",
    url = "https://example.com/$id",
    feedTitle = "Feed $feedId",
    feedId = feedId,
    isRead = isRead,
)

private fun loadMoreMakeViewModel(
    itemsFlow: MutableStateFlow<List<ArticleItem>>,
    coroutineScope: CoroutineScope = CoroutineScope(Job()),
): FeedViewModel {
    val settings: Settings = LoadMoreInMemorySettings()
    return FeedViewModel(
        repository = LoadMoreFakeFeedRepository(itemsFlow),
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(LoadMoreInMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}

/**
 * Scrolls [host] to the bottom of its scrollable area and dispatches the
 * `scroll` event that [renderArticleList] listens for — mirrors how the other
 * DOM-event-driven tests in this module (e.g. [ArticleListSelectionTest]'s
 * KeyboardEvent dispatches) drive production listeners directly rather than
 * relying on a real user gesture, which headless Chrome under Karma won't
 * synthesize on its own.
 */
private fun scrollToBottom(host: HTMLElement) {
    host.scrollTop = (host.scrollHeight - host.clientHeight).toDouble()
    host.dispatchEvent(Event("scroll"))
}

/**
 * BUG-46 was the original report that it "doesn't seem possible to load more
 * than one page" of articles on the web article list (root cause: `hasMore`'s
 * upstream `combine()` never ran without an active collector). Ticket #113
 * replaced the manual "Load more" button that BUG-46 fixed with automatic,
 * scroll-triggered loading — these tests now drive scroll position instead of
 * clicking a button.
 *
 * They still exercise the real `renderArticleList` production entrypoint (not
 * a reimplementation) against a fake repository seeded with more than
 * [FeedViewModel.DEFAULT_PAGE_SIZE] articles, and give the host element a
 * fixed height + `overflow-y: auto` so `scrollHeight`/`clientHeight`/`scrollTop`
 * behave like the real `#feed-screen-article-list` column.
 */
class ArticleListLoadMoreTest {

    private fun makeScrollableHost(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        // Small fixed viewport with real rows tall enough to overflow it, so
        // scrollHeight > clientHeight and scrollTop is meaningful.
        host.setAttribute("style", "height: 300px; overflow-y: auto;")
        document.body!!.appendChild(host)
        return host
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun scrollingNearBottomTriggersLoadMoreAndAppendsArticlesBeyondFirstPage(): dynamic = GlobalScope.promise {
        // 65 unread articles — one full page (50) plus a partial second page (15).
        val articles = (1..65).map { loadMoreMakeArticle(id = "$it") }
        val itemsFlow = MutableStateFlow(articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = makeScrollableHost()

        try {
            renderArticleList(host, vm)
            // Let the initial render + all GlobalScope subscriptions (including
            // the hasMore collector) settle.
            repeat(10) { yield() }
            delay(20)

            // First page: only DEFAULT_PAGE_SIZE (50) rows are rendered.
            val rowsAfterInitialLoad = host.querySelectorAll("[data-article-row]")
            assertEquals(
                FeedViewModel.DEFAULT_PAGE_SIZE,
                rowsAfterInitialLoad.length,
                "Initial window must be capped at DEFAULT_PAGE_SIZE",
            )

            // The loading indicator must be present — this is the crux of
            // BUG-46's original fix: pre-fix, hasMore.value stayed false and no
            // "more articles" affordance ever rendered.
            val loadMoreIndicator = host.querySelector("[data-load-more-indicator]") as? HTMLElement
            assertNotNull(loadMoreIndicator, "Loading indicator must render when more than one page of articles exists")
            assertTrue(vm.hasMore.value, "hasMore must be true after the article list has been rendered")

            // No manual button exists anymore (#113 removed it).
            assertNull(host.querySelector("[data-load-more]"), "The manual Load more button must be removed")

            // Scroll to the bottom — the real user gesture, now wired via the
            // `scroll` listener registered on the host container in renderArticleList().
            scrollToBottom(host)
            repeat(10) { yield() }
            delay(20)

            // All 65 articles must now be rendered, and the indicator must be gone
            // since every article matching the filter is loaded.
            val rowsAfterLoadMore = host.querySelectorAll("[data-article-row]")
            assertEquals(65, rowsAfterLoadMore.length, "Scrolling near the bottom must append the remaining articles")

            val ids = (0 until rowsAfterLoadMore.length).map {
                (rowsAfterLoadMore.item(it) as HTMLElement).getAttribute("data-article-row")
            }
            assertTrue(ids.contains("65"), "The last article (beyond the first page) must be rendered after scroll-triggered load")

            assertNull(
                host.querySelector("[data-load-more-indicator]"),
                "Loading indicator must disappear once all articles are loaded",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun loadMoreIndicatorAbsentWhenAllArticlesFitOnOnePage(): dynamic = GlobalScope.promise {
        val articles = (1..10).map { loadMoreMakeArticle(id = "$it") }
        val itemsFlow = MutableStateFlow(articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = makeScrollableHost()

        try {
            renderArticleList(host, vm)
            repeat(10) { yield() }
            delay(20)

            assertEquals(10, host.querySelectorAll("[data-article-row]").length)
            assertNull(
                host.querySelector("[data-load-more-indicator]"),
                "Loading indicator must not render when every article already fits in one page",
            )

            // Scrolling (even to the bottom, of a non-overflowing list) must never
            // call loadMore() when hasMore is false — the stop condition.
            scrollToBottom(host)
            repeat(10) { yield() }
            delay(20)

            assertFalse(vm.hasMore.value)
            assertEquals(10, host.querySelectorAll("[data-article-row]").length)
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    // PR #150 review: with 3+ pages, the first auto-load grows the window
    // 50→100 while hasMore recomputes to `true` *without emitting* (StateFlow
    // conflation) — a guard reset keyed only on hasMore left
    // loadMoreFetchInFlight stuck and infinite scroll permanently dead at 100
    // articles. The reset must also be driven by the articleItems emission,
    // which fires on every window growth. Two consecutive scrolls must load
    // page 2 *and* page 3.
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun consecutiveScrollsKeepLoadingPagesWhenHasMoreStaysTrue(): dynamic = GlobalScope.promise {
        // 120 articles — two full pages plus a partial third. After the first
        // auto-load (window 50→100, 100 items loaded) hasMore recomputes to
        // true→true per its >= boundary and never emits; only the second
        // auto-load (window →150, 120 items) flips it to false.
        val articles = (1..120).map { loadMoreMakeArticle(id = "$it") }
        val itemsFlow = MutableStateFlow(articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = makeScrollableHost()

        try {
            renderArticleList(host, vm)
            repeat(10) { yield() }
            delay(20)

            assertEquals(FeedViewModel.DEFAULT_PAGE_SIZE, host.querySelectorAll("[data-article-row]").length)

            // First scroll: loads page 2. hasMore stays true (100 loaded, 120 exist).
            scrollToBottom(host)
            repeat(10) { yield() }
            delay(20)
            assertEquals(
                2 * FeedViewModel.DEFAULT_PAGE_SIZE,
                host.querySelectorAll("[data-article-row]").length,
                "First scroll must load the second page",
            )
            assertTrue(vm.hasMore.value, "hasMore must still be true with a third page available")
            assertNotNull(
                host.querySelector("[data-load-more-indicator]"),
                "Indicator must still render while a third page remains",
            )

            // Second scroll: pre-fix, the stuck fetch-in-flight guard made this
            // a no-op and the list was pinned at 100 articles forever.
            scrollToBottom(host)
            repeat(10) { yield() }
            delay(20)
            val rows = host.querySelectorAll("[data-article-row]")
            assertEquals(120, rows.length, "Second scroll must load the third page (guard must have reset)")
            assertEquals(
                "120",
                (rows.item(rows.length - 1) as HTMLElement).getAttribute("data-article-row"),
                "The very last article must be rendered",
            )
            assertNull(
                host.querySelector("[data-load-more-indicator]"),
                "Indicator must disappear once all three pages are loaded",
            )
            assertFalse(vm.hasMore.value)
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    // Fetch-in-flight guard: repeated scroll events while the same page is
    // still the "current" state (hasMore hasn't re-resolved) must not
    // double-fire loadMore(). loadMore() itself is idempotent about window size
    // once all articles are loaded, so we assert on the row count settling
    // rather than a call counter (the production code has no test-visible
    // counter — the guard's effect is observable via final state).
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun repeatedScrollEventsNearBottomDoNotDoubleFireBeyondAvailableArticles(): dynamic = GlobalScope.promise {
        val articles = (1..FeedViewModel.DEFAULT_PAGE_SIZE).map { loadMoreMakeArticle(id = "$it") }
        val itemsFlow = MutableStateFlow(articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = makeScrollableHost()

        try {
            renderArticleList(host, vm)
            repeat(10) { yield() }
            delay(20)

            assertEquals(FeedViewModel.DEFAULT_PAGE_SIZE, host.querySelectorAll("[data-article-row]").length)
            assertNotNull(
                host.querySelector("[data-load-more-indicator]"),
                "Indicator must render at exactly DEFAULT_PAGE_SIZE per hasMore's >= boundary",
            )

            // Fire several scroll events in a row before the guard's reset
            // (driven by hasMore's collector) would have a chance to re-arm
            // incorrectly.
            repeat(5) {
                scrollToBottom(host)
            }
            repeat(10) { yield() }
            delay(20)

            // No articles exist beyond the boundary, so the row count must not
            // change no matter how many scroll events fired.
            assertEquals(
                FeedViewModel.DEFAULT_PAGE_SIZE,
                host.querySelectorAll("[data-article-row]").length,
                "No articles exist beyond the boundary, so the row count must not change",
            )
            assertNull(
                host.querySelector("[data-load-more-indicator]"),
                "Indicator must disappear once the spurious loads confirm no more articles exist",
            )
            assertFalse(vm.hasMore.value)
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    // Filter-change coverage flagged in PR #146 review: the fix comment in
    // ArticleList.kt claims the collector makes the indicator "react to
    // loadMore()/filter changes". This loads a second page under
    // Route.AllArticles via scroll, then selects a feed with far fewer
    // articles and confirms the indicator (and hasMore) reflect the new
    // filter's smaller, single-page count.
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun loadMoreIndicatorReflectsFilterChangeAfterLoadingASecondPageViaScroll(): dynamic = GlobalScope.promise {
        val feed1Articles = (1..55).map { loadMoreMakeArticle(id = "f1-$it", feedId = 1) }
        val feed2Articles = (1..5).map { loadMoreMakeArticle(id = "f2-$it", feedId = 2) }
        val itemsFlow = MutableStateFlow(feed1Articles + feed2Articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = makeScrollableHost()

        try {
            renderArticleList(host, vm)
            repeat(10) { yield() }
            delay(20)

            assertNotNull(
                host.querySelector("[data-load-more-indicator]"),
                "Loading indicator must be present before loading the second page",
            )
            scrollToBottom(host)
            repeat(10) { yield() }
            delay(20)
            assertEquals(60, host.querySelectorAll("[data-article-row]").length)
            assertNull(host.querySelector("[data-load-more-indicator]"))

            // Switch to feed 2 (only 5 articles) — selectFeed() resets pageCount
            // and swaps the filter; the collector must react to both.
            vm.selectFeed(2)
            repeat(10) { yield() }
            delay(20)

            assertEquals(5, host.querySelectorAll("[data-article-row]").length)
            assertNull(
                host.querySelector("[data-load-more-indicator]"),
                "Loading indicator must reflect the new filter's smaller count",
            )
            assertFalse(vm.hasMore.value, "hasMore must reset to false for the new filter")
        } finally {
            host.remove()
            scope.cancel()
        }
    }
}
