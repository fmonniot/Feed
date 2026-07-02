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
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = FeedAddResponse(id = 99, message = "ok")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
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
 * BUG-46: reproduces the report that it "doesn't seem possible to load more
 * than one page" of articles on the web article list.
 *
 * Root cause: [FeedViewModel.hasMore] is a `WhileSubscribed(5000)` StateFlow.
 * `ArticleList.kt` read `viewModel.hasMore.value` directly inside
 * `updateArticleListRows` but never `collect`ed the flow anywhere, so its
 * upstream `combine()` never started running — `.value` stayed pinned at the
 * seeded `false` and the "Load more" button never rendered, no matter how
 * many articles existed beyond the first page.
 *
 * These tests drive the real `renderArticleList` production entrypoint (not
 * a reimplementation) against a fake repository seeded with more than
 * [FeedViewModel.DEFAULT_PAGE_SIZE] articles.
 */
class ArticleListLoadMoreTest {

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun loadMoreButtonAppearsAndAppendsArticlesBeyondFirstPage(): dynamic = GlobalScope.promise {
        // 65 unread articles — one full page (50) plus a partial second page (15).
        val articles = (1..65).map { loadMoreMakeArticle(id = "$it") }
        val itemsFlow = MutableStateFlow(articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        try {
            renderArticleList(host, vm)
            // Let the initial render + all GlobalScope subscriptions (including
            // the new hasMore collector) settle.
            repeat(10) { yield() }
            delay(20)

            // First page: only DEFAULT_PAGE_SIZE (50) rows are rendered.
            val rowsAfterInitialLoad = host.querySelectorAll("[data-article-row]")
            assertEquals(
                FeedViewModel.DEFAULT_PAGE_SIZE,
                rowsAfterInitialLoad.length,
                "Initial window must be capped at DEFAULT_PAGE_SIZE",
            )

            // The "Load more" button must be present and discoverable — this is
            // the crux of BUG-46: pre-fix, hasMore.value stayed false and this
            // button never rendered.
            val loadMoreButton = host.querySelector("[data-load-more]") as? HTMLElement
            assertNotNull(loadMoreButton, "Load more button must render when more than one page of articles exists")
            assertTrue(vm.hasMore.value, "hasMore must be true after the article list has been rendered")

            // Click it — this is the real user gesture, wired via the click
            // delegate registered on the rows container in renderArticleList().
            loadMoreButton.click()
            repeat(10) { yield() }
            delay(20)

            // All 65 articles must now be rendered, and the button must be gone
            // since every article matching the filter is loaded.
            val rowsAfterLoadMore = host.querySelectorAll("[data-article-row]")
            assertEquals(65, rowsAfterLoadMore.length, "Clicking Load more must append the remaining articles")

            val ids = (0 until rowsAfterLoadMore.length).map {
                (rowsAfterLoadMore.item(it) as HTMLElement).getAttribute("data-article-row")
            }
            assertTrue(ids.contains("65"), "The last article (beyond the first page) must be rendered after Load more")

            assertNull(
                host.querySelector("[data-load-more]"),
                "Load more button must disappear once all articles are loaded",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun loadMoreButtonAbsentWhenAllArticlesFitOnOnePage(): dynamic = GlobalScope.promise {
        val articles = (1..10).map { loadMoreMakeArticle(id = "$it") }
        val itemsFlow = MutableStateFlow(articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        try {
            renderArticleList(host, vm)
            repeat(10) { yield() }
            delay(20)

            assertEquals(10, host.querySelectorAll("[data-article-row]").length)
            assertNull(
                host.querySelector("[data-load-more]"),
                "Load more button must not render when every article already fits in one page",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    // Boundary case flagged in PR #146 review: FeedViewModel.hasMore uses
    // `items.size >= windowSize`, so at exactly DEFAULT_PAGE_SIZE articles the
    // button renders even though nothing more can be loaded (documented as
    // deliberate in FeedViewModel.hasMore's kdoc). Pins that spurious-but-intended
    // behavior from both directions, rather than only the 65-article `>` case.
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun loadMoreButtonAppearsAtExactPageSizeBoundaryAndDisappearsOnceClicked(): dynamic = GlobalScope.promise {
        val articles = (1..FeedViewModel.DEFAULT_PAGE_SIZE).map { loadMoreMakeArticle(id = "$it") }
        val itemsFlow = MutableStateFlow(articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        try {
            renderArticleList(host, vm)
            repeat(10) { yield() }
            delay(20)

            assertEquals(FeedViewModel.DEFAULT_PAGE_SIZE, host.querySelectorAll("[data-article-row]").length)
            val loadMoreButton = host.querySelector("[data-load-more]") as? HTMLElement
            assertNotNull(loadMoreButton, "Button must render at exactly DEFAULT_PAGE_SIZE per hasMore's >= boundary")

            loadMoreButton.click()
            repeat(10) { yield() }
            delay(20)

            assertEquals(
                FeedViewModel.DEFAULT_PAGE_SIZE,
                host.querySelectorAll("[data-article-row]").length,
                "No articles exist beyond the boundary, so the row count must not change",
            )
            assertNull(
                host.querySelector("[data-load-more]"),
                "Button must disappear once the spurious click confirms no more articles exist",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    // Filter-change coverage flagged in PR #146 review: the fix comment in
    // ArticleList.kt claims the collector makes the button "react to
    // loadMore()/filter changes", but no test exercised the filter half. This
    // loads a second page under Route.AllArticles, then selects a feed with far
    // fewer articles and confirms the button (and hasMore) reflect the new
    // filter's smaller, single-page count.
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun loadMoreButtonReflectsFilterChangeAfterLoadingASecondPage(): dynamic = GlobalScope.promise {
        val feed1Articles = (1..55).map { loadMoreMakeArticle(id = "f1-$it", feedId = 1) }
        val feed2Articles = (1..5).map { loadMoreMakeArticle(id = "f2-$it", feedId = 2) }
        val itemsFlow = MutableStateFlow(feed1Articles + feed2Articles)
        val scope = CoroutineScope(Job())
        val vm = loadMoreMakeViewModel(itemsFlow, scope)

        navigate(Route.AllArticles)
        repeat(5) { yield() }
        delay(20)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        try {
            renderArticleList(host, vm)
            repeat(10) { yield() }
            delay(20)

            val loadMoreButton = host.querySelector("[data-load-more]") as? HTMLElement
            assertNotNull(loadMoreButton, "Load more button must be present before loading the second page")
            loadMoreButton.click()
            repeat(10) { yield() }
            delay(20)
            assertEquals(60, host.querySelectorAll("[data-article-row]").length)
            assertNull(host.querySelector("[data-load-more]"))

            // Switch to feed 2 (only 5 articles) — selectFeed() resets pageCount
            // and swaps the filter; the collector must react to both.
            vm.selectFeed(2)
            repeat(10) { yield() }
            delay(20)

            assertEquals(5, host.querySelectorAll("[data-article-row]").length)
            assertNull(
                host.querySelector("[data-load-more]"),
                "Load more button must reflect the new filter's smaller count",
            )
            assertFalse(vm.hasMore.value, "hasMore must reset to false for the new filter")
        } finally {
            host.remove()
            scope.cancel()
        }
    }
}
