package eu.monniot.feed.web.ui.feed

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.FeedUiItem
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals

// ---------------------------------------------------------------------------
// Minimal in-memory Settings for constructing FeedViewModel
// ---------------------------------------------------------------------------

private class StubSettings : Settings {
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

// ---------------------------------------------------------------------------
// FakeFeedRepository that supports per-feed queries and real feed list
// ---------------------------------------------------------------------------

private class BadgeFakeFeedRepository(
    private val feedList: List<Feed>,
    val itemsFlow: MutableStateFlow<List<ArticleItem>>,
) : FeedRepository {

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        itemsFlow.map { items ->
            val filtered = when (filter) {
                is ArticleFilter.All -> items
                is ArticleFilter.UnreadOnly -> items.filter { !it.isRead }
                is ArticleFilter.ByFeed -> items.filter { it.feedId == filter.feedId }
            }
            filtered.drop(window.first).take(window.last - window.first + 1)
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

    override suspend fun getFeeds(): List<Feed> = feedList

    override suspend fun refresh() {}
    override suspend fun refreshUpstream(): RefreshResult = RefreshResult.Success(0)
    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult = RefreshResult.Success(0)
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun markAllAsRead() {}
    override suspend fun markFeedAsRead(feedId: Int) {}
    override suspend fun markArticlesAsRead(articleIds: List<Int>) {}
    override suspend fun markArticlesAsUnread(articleIds: List<Int>) {}
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
    override suspend fun getRetention(): Int? = null
    override suspend fun setRetention(days: Int?) {}
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun makeFeedApiItem(
    id: Int,
    unreadCount: Int = 0,
) = Feed(
    id = id,
    url = "https://example.com/feed/$id",
    title = "Feed $id",
    custom_title = null,
    is_paused = false,
    fetch_interval_minutes = 60,
    error_count = 0,
    last_fetched = null,
    unread_count = unreadCount,
    category_id = null,
)

private fun makeFeedUiItem(
    id: Int,
    unreadCount: Int = 0,
    title: String = "Feed $id",
) = FeedUiItem(
    id = id,
    displayTitle = title,
    rawCustomTitle = null,
    url = "https://example.com/feed/$id",
    unreadCount = unreadCount,
    isPaused = false,
    errorCount = 0,
    fetchIntervalMinutes = 60,
)

private fun makeArticleItem(id: String, feedId: Int, isRead: Boolean = false) = ArticleItem(
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
    feedList: List<Feed>,
    itemsFlow: MutableStateFlow<List<ArticleItem>>,
    coroutineScope: CoroutineScope = CoroutineScope(Job()),
): FeedViewModel {
    val settings: Settings = StubSettings()
    return FeedViewModel(
        repository = BadgeFakeFeedRepository(feedList, itemsFlow),
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(StubSettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/**
 * #115: Unread badge on sidebar source items.
 *
 * Covers:
 *  - Badge renders when unread count > 0.
 *  - Badge is hidden (not rendered) when unread count == 0.
 *  - Badge updates reactively when articles are marked read.
 *  - liveUnreadCount overrides the server-supplied FeedUiItem.unreadCount.
 */
class SidebarUnreadBadgeTest {

    // -- Static rendering via feedRow ----------------------------------------

    /** Badge element must be present and show the count when liveUnreadCount > 0. */
    @Test
    fun feedRow_badge_rendersWhenUnreadCountPositive() {
        val feed = makeFeedUiItem(id = 1, unreadCount = 0)
        val host = document.createElement("div") as HTMLElement
        host.append { feedRow(feed, isSelected = false, liveUnreadCount = 5) }

        val badge = host.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNotNull(badge, "badge must be rendered when liveUnreadCount = 5")
        assertEquals("5", badge.textContent, "badge text must match the count")
    }

    /** Badge must not be rendered at all (not even as '0') when liveUnreadCount == 0. */
    @Test
    fun feedRow_badge_hiddenWhenUnreadCountZero() {
        val feed = makeFeedUiItem(id = 1, unreadCount = 3)
        val host = document.createElement("div") as HTMLElement
        host.append { feedRow(feed, isSelected = false, liveUnreadCount = 0) }

        val badge = host.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNull(badge, "badge must NOT be rendered when liveUnreadCount = 0")
    }

    /** When no liveUnreadCount is supplied, feedRow falls back to feed.unreadCount. */
    @Test
    fun feedRow_badge_defaultsToFeedUnreadCount() {
        val feed = makeFeedUiItem(id = 1, unreadCount = 7)
        val host = document.createElement("div") as HTMLElement
        host.append { feedRow(feed, isSelected = false) }  // no liveUnreadCount

        val badge = host.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNotNull(badge, "badge must be rendered when feed.unreadCount = 7 (default)")
        assertEquals("7", badge.textContent)
    }

    /** feedRow with no liveUnreadCount and feed.unreadCount == 0 must not render badge. */
    @Test
    fun feedRow_badge_hiddenWhenFeedUnreadCountZeroAndNoOverride() {
        val feed = makeFeedUiItem(id = 1, unreadCount = 0)
        val host = document.createElement("div") as HTMLElement
        host.append { feedRow(feed, isSelected = false) }

        val badge = host.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNull(badge, "badge must NOT render when both liveUnreadCount and feed.unreadCount are 0")
    }

    // -- renderFeedListContent: live counts override server counts -------------

    /**
     * When unreadCounts contains a live count for a feed, the badge must display
     * the live count rather than the server-supplied FeedUiItem.unreadCount.
     */
    @Test
    fun renderFeedListContent_badge_usesLiveCountOverServerCount() {
        val feed = makeFeedUiItem(id = 1, unreadCount = 3)  // server says 3
        val host = document.createElement("div") as HTMLElement
        host.append {
            renderFeedListContent(
                feeds = listOf(feed),
                categories = emptyList(),
                selectedFeedId = null,
                unreadCounts = mapOf(1 to 9),   // live count is 9
            )
        }

        val btn = host.querySelector("[data-feed-item='1']") as? HTMLElement
        assertNotNull(btn)
        val badge = btn.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNotNull(badge, "badge must be rendered")
        assertEquals("9", badge.textContent, "badge must show the live count (9), not the server count (3)")
    }

    /**
     * When the live count for a feed is 0, the badge must be hidden even if
     * FeedUiItem.unreadCount (server snapshot) is non-zero.
     */
    @Test
    fun renderFeedListContent_badge_hiddenWhenLiveCountZeroEvenIfServerCountNonZero() {
        val feed = makeFeedUiItem(id = 1, unreadCount = 5)  // server says 5
        val host = document.createElement("div") as HTMLElement
        host.append {
            renderFeedListContent(
                feeds = listOf(feed),
                categories = emptyList(),
                selectedFeedId = null,
                unreadCounts = mapOf(1 to 0),   // live count is 0
            )
        }

        val btn = host.querySelector("[data-feed-item='1']") as? HTMLElement
        assertNotNull(btn)
        val badge = btn.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNull(badge, "badge must be hidden when live count is 0, even if server count is 5")
    }

    // -- Reactive update: badge reflects live store state ---------------------

    /**
     * When an article is marked read, the per-feed unread badge in the sidebar
     * must update reactively to reflect the new count without requiring a
     * loadFeeds() round-trip to the server.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun sidebar_badge_updatesWhenArticleMarkedRead(): dynamic = GlobalScope.promise {
        // Feed 1 has 3 unread articles; feed 2 has 2 unread articles.
        val itemsFlow = MutableStateFlow(
            listOf(
                makeArticleItem("a1", feedId = 1, isRead = false),
                makeArticleItem("a2", feedId = 1, isRead = false),
                makeArticleItem("a3", feedId = 1, isRead = false),
                makeArticleItem("b1", feedId = 2, isRead = false),
                makeArticleItem("b2", feedId = 2, isRead = false),
            )
        )
        val feedList = listOf(
            makeFeedApiItem(id = 1, unreadCount = 0),  // server count is stale (0)
            makeFeedApiItem(id = 2, unreadCount = 0),
        )
        val scope = CoroutineScope(Job())
        val vm = makeViewModel(feedList, itemsFlow, scope)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        renderSidebar(host, vm)

        // Populate _feeds by calling loadFeeds() (triggers getFeeds() on the repo).
        vm.loadFeeds()
        repeat(10) { yield() }

        // After loadFeeds, perFeedUnreadCounts should reflect the article store.
        // Feed 1: 3 unread, feed 2: 2 unread.
        val btn1Before = host.querySelector("[data-feed-item='1']") as? HTMLElement
        assertNotNull(btn1Before, "feed 1 button must be rendered after loadFeeds")
        val badge1Before = btn1Before.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNotNull(badge1Before, "feed 1 badge must be visible (3 unread)")
        assertEquals("3", badge1Before.textContent, "feed 1 badge must show 3 unread")

        val btn2Before = host.querySelector("[data-feed-item='2']") as? HTMLElement
        assertNotNull(btn2Before)
        val badge2Before = btn2Before.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNotNull(badge2Before, "feed 2 badge must be visible (2 unread)")
        assertEquals("2", badge2Before.textContent, "feed 2 badge must show 2 unread")

        // Mark all feed-1 articles as read (simulate markAsRead).
        itemsFlow.value = itemsFlow.value.map { art ->
            if (art.feedId == 1) art.copy(isRead = true) else art
        }
        repeat(10) { yield() }

        // Feed 1 badge should disappear (0 unread); feed 2 badge unchanged.
        val btn1After = host.querySelector("[data-feed-item='1']") as? HTMLElement
        assertNotNull(btn1After)
        val badge1After = btn1After.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNull(badge1After, "feed 1 badge must be hidden after all articles marked read")

        val btn2After = host.querySelector("[data-feed-item='2']") as? HTMLElement
        assertNotNull(btn2After)
        val badge2After = btn2After.querySelector("[data-part='unread-badge']") as? HTMLElement
        assertNotNull(badge2After, "feed 2 badge must still be visible (unchanged)")
        assertEquals("2", badge2After.textContent, "feed 2 badge must still show 2")

        host.remove()
        scope.cancel()
    }

    /**
     * Cold-start tradeoff (#115 review): after loadFeeds() populates the feed
     * list but before the local mirror has synced any articles,
     * perFeedUnreadCounts reports 0 for every feed. That 0 overrides the
     * server-supplied unread_count, so badges stay hidden until the first sync
     * — deliberately consistent with the mirror-backed globalUnreadCount. This
     * test pins that decision so a future change to prefer the server snapshot
     * on cold start is a conscious one.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun sidebar_badge_hiddenOnColdStartWhenMirrorEmptyDespiteServerCount(): dynamic = GlobalScope.promise {
        // Empty local mirror: no articles synced yet.
        val itemsFlow = MutableStateFlow(emptyList<ArticleItem>())
        // ...but the server snapshot reports unread articles for both feeds.
        val feedList = listOf(
            makeFeedApiItem(id = 1, unreadCount = 5),
            makeFeedApiItem(id = 2, unreadCount = 2),
        )
        val scope = CoroutineScope(Job())
        val vm = makeViewModel(feedList, itemsFlow, scope)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        renderSidebar(host, vm)
        vm.loadFeeds()
        repeat(10) { yield() }

        // Both feeds render, but the empty mirror makes perFeedUnreadCounts emit
        // 0 for each, so the badges are hidden even though the server said 5/2.
        val btn1 = host.querySelector("[data-feed-item='1']") as? HTMLElement
        assertNotNull(btn1, "feed 1 must be rendered after loadFeeds")
        assertNull(
            btn1.querySelector("[data-part='unread-badge']"),
            "cold-start: feed 1 badge hidden while mirror empty, despite server count 5",
        )

        val btn2 = host.querySelector("[data-feed-item='2']") as? HTMLElement
        assertNotNull(btn2, "feed 2 must be rendered after loadFeeds")
        assertNull(
            btn2.querySelector("[data-part='unread-badge']"),
            "cold-start: feed 2 badge hidden while mirror empty, despite server count 2",
        )

        host.remove()
        scope.cancel()
    }
}
