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
import kotlin.test.assertNotEquals

// ---------------------------------------------------------------------------
// Inline test doubles (duplicated per-file, following the pattern already
// established in ArticleListLoadMoreTest.kt / SidebarUnreadBadgeTest.kt — the
// web test module cannot access shared/commonTest fakes).
// ---------------------------------------------------------------------------

private class ScrollResetInMemorySettings : Settings {
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

private class ScrollResetFakeFeedRepository(
    val itemsFlow: MutableStateFlow<List<ArticleItem>>,
) : FeedRepository {
    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        itemsFlow.map { items ->
            val filtered = when (filter) {
                is ArticleFilter.All -> items
                is ArticleFilter.UnreadOnly -> items.filter { !it.isRead }
                is ArticleFilter.ByFeed -> items.filter { it.feedId == filter.feedId }
            }
            val start = window.first.coerceAtMost(filtered.size)
            val end = (window.last + 1).coerceAtMost(filtered.size)
            filtered.subList(start, end)
        }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
        itemsFlow.map { items -> items.count { !it.isRead } }

    override fun observeTotalCount(): Flow<Int> = itemsFlow.map { it.size }

    override fun observeCount(filter: ArticleFilter): Flow<Int> =
        itemsFlow.map { items -> items.size }

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

private fun scrollResetMakeArticle(id: String) = ArticleItem(
    id = id,
    title = "Article $id",
    // Long body so the rendered content overflows the fixed-height host.
    description = "<p>${"Lorem ipsum dolor sit amet. ".repeat(200)}</p>",
    pubDate = "",
    source = "test",
    url = "https://example.com/$id",
    feedTitle = "Feed 1",
    feedId = 1,
    isRead = false,
)

private fun scrollResetMakeViewModel(
    itemsFlow: MutableStateFlow<List<ArticleItem>>,
    coroutineScope: CoroutineScope = CoroutineScope(Job()),
): FeedViewModel {
    val settings: Settings = ScrollResetInMemorySettings()
    return FeedViewModel(
        repository = ScrollResetFakeFeedRepository(itemsFlow),
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(ScrollResetInMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}

/**
 * BUG-52: the reader pane's scroll container (`#reader-pane-content`) is never
 * replaced across re-renders — only its children are — so its `scrollTop`
 * survived switching articles, opening the new article mid-content instead of
 * at the top. Verifies the fix resets scroll exactly when the selected
 * article changes, and not on unrelated re-renders (e.g. font size changes).
 */
class ReaderPaneScrollResetTest {

    private fun makeHost(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.setAttribute("style", "height: 200px; overflow-y: auto;")
        document.body!!.appendChild(host)
        return host
    }

    private fun contentEl(host: HTMLElement): HTMLElement =
        host.querySelector("#reader-pane-content") as HTMLElement

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun switchingArticleResetsScrollToTop(): dynamic = GlobalScope.promise {
        val itemsFlow = MutableStateFlow(
            listOf(scrollResetMakeArticle("1"), scrollResetMakeArticle("2"))
        )
        val scope = CoroutineScope(Job())
        val vm = scrollResetMakeViewModel(itemsFlow, scope)
        val host = makeHost()

        try {
            renderReaderPane(host, vm)
            repeat(10) { yield() }
            delay(20)

            vm.selectArticle("1")
            repeat(10) { yield() }
            delay(20)

            val content = contentEl(host)
            content.scrollTop = 80.0
            content.dispatchEvent(Event("scroll"))
            repeat(5) { yield() }
            assertNotEquals(0.0, content.scrollTop, "precondition: scroll must have moved away from top")

            vm.selectArticle("2")
            repeat(10) { yield() }
            delay(20)

            assertEquals(
                0.0,
                contentEl(host).scrollTop,
                "scroll position must reset to top when switching to a different article",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun changingFontSizeDoesNotResetScrollForSameArticle(): dynamic = GlobalScope.promise {
        val itemsFlow = MutableStateFlow(listOf(scrollResetMakeArticle("1")))
        val scope = CoroutineScope(Job())
        val vm = scrollResetMakeViewModel(itemsFlow, scope)
        val host = makeHost()

        try {
            renderReaderPane(host, vm)
            repeat(10) { yield() }
            delay(20)

            vm.selectArticle("1")
            repeat(10) { yield() }
            delay(20)

            val content = contentEl(host)
            content.scrollTop = 80.0
            content.dispatchEvent(Event("scroll"))
            repeat(5) { yield() }
            assertNotEquals(0.0, content.scrollTop, "precondition: scroll must have moved away from top")

            vm.updateFontSize(22)
            repeat(10) { yield() }
            delay(20)

            assertNotEquals(
                0.0,
                contentEl(host).scrollTop,
                "unrelated re-renders (font size) must not reset scroll for the same article",
            )
        } finally {
            host.remove()
            scope.cancel()
        }
    }
}
