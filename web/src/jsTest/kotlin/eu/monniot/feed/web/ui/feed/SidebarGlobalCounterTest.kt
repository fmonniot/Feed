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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals

// ── Inline test doubles ──────────────────────────────────────────────────────
// The web test module cannot access shared/commonTest, so we duplicate the
// minimal fakes needed to construct a FeedViewModel (see
// LoginServerUrlIntegrationTest.kt for the same pattern).

private class InMemorySettings : Settings {
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
 * [observeTotalCount] (always global, matching the real store contract:
 * [ArticleStore.observeTotalCount] is unfiltered and
 * `observeUnreadCount(ArticleFilter.All)` counts every unread article).
 */
private class FakeFeedRepository(
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

private fun makeArticle(id: String, feedId: Int, isRead: Boolean = false) = ArticleItem(
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

private fun makeViewModel(
    itemsFlow: MutableStateFlow<List<ArticleItem>>,
    coroutineScope: CoroutineScope = CoroutineScope(Job()),
): FeedViewModel {
    val settings: Settings = InMemorySettings()
    return FeedViewModel(
        repository = FakeFeedRepository(itemsFlow),
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(InMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}

private fun navCounterText(host: HTMLElement, label: String): String? {
    val btn = host.querySelector("[data-nav-item='$label']") as? HTMLElement
    // navItem renders <span>label</span><span>count</span> — the count is the
    // second span, when present.
    val spans = btn?.querySelectorAll("span")
    if (spans == null || spans.length < 2) return null
    return (spans.item(1) as? HTMLElement)?.textContent
}

/**
 * BUG-43: the sidebar's "All articles" counter must always show the global
 * total article count, never the currently active filter's (scoped) count —
 * and likewise "Unread" must always show the global unread count, not a
 * per-feed one.
 */
class SidebarGlobalCounterTest {

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun allArticlesCounterStaysGlobalAcrossFilterAndFeedSwitches(): dynamic = GlobalScope.promise {
        // 3 articles across two feeds; 2 unread, 1 read.
        val itemsFlow = MutableStateFlow(
            listOf(
                makeArticle(id = "1", feedId = 1, isRead = false),
                makeArticle(id = "2", feedId = 1, isRead = true),
                makeArticle(id = "3", feedId = 2, isRead = false),
            )
        )
        val scope = CoroutineScope(Job())
        val vm = makeViewModel(itemsFlow, scope)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        renderSidebar(host, vm)
        repeat(5) { yield() }

        // Baseline: Unread view (no feed selected).
        vm.selectFeed(null, showAll = false)
        repeat(5) { yield() }
        assertEquals("3", navCounterText(host, "All articles"), "All articles must show the global total")
        assertEquals("2", navCounterText(host, "Unread"), "Unread must show the global unread count")

        // Switch to the All-articles view.
        vm.selectFeed(null, showAll = true)
        repeat(5) { yield() }
        assertEquals(
            "3", navCounterText(host, "All articles"),
            "All articles counter must not change when switching to the All-articles view",
        )
        assertEquals(
            "2", navCounterText(host, "Unread"),
            "Unread counter must not change when switching to the All-articles view",
        )

        // Select a single feed — pre-fix this scoped both counters down to
        // that feed's own counts (BUG-43 symptom #2).
        vm.selectFeed(1)
        repeat(5) { yield() }
        assertEquals(
            "3", navCounterText(host, "All articles"),
            "All articles counter must stay global when a feed is selected",
        )
        assertEquals(
            "2", navCounterText(host, "Unread"),
            "Unread counter must stay global when a feed is selected",
        )

        // Switch to the other feed.
        vm.selectFeed(2)
        repeat(5) { yield() }
        assertEquals(
            "3", navCounterText(host, "All articles"),
            "All articles counter must stay global after switching feeds",
        )
        assertEquals(
            "2", navCounterText(host, "Unread"),
            "Unread counter must stay global after switching feeds",
        )

        // Mutate the article set — mark article 1 as read. This pins
        // reactivity, not just globality: a stale flow (e.g. a broken
        // distinctUntilChanged/version wiring) would still pass every
        // assertion above since itemsFlow never changed until now.
        itemsFlow.value = itemsFlow.value.map { if (it.id == "1") it.copy(isRead = true) else it }
        repeat(5) { yield() }
        assertEquals(
            "3", navCounterText(host, "All articles"),
            "All articles counter must stay at the total after a read-state change",
        )
        assertEquals(
            "1", navCounterText(host, "Unread"),
            "Unread counter must drop to reflect the read-state change",
        )

        host.remove()
        scope.cancel()
    }
}
