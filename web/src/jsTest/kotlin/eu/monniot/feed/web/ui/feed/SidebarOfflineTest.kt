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
import eu.monniot.feed.shared.sync.FeedMeta
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * BUG-63 part 2: sidebar rendering when offline (or the server unreachable) from a cold
 * start — no `getFeeds()` / `getCategories()` call has ever succeeded this session, but the
 * persisted `FeedStore` / `CategoryStore` already has data from a previous session (modeled
 * here as [OfflineFakeFeedRepository]'s `observeCachedFeeds`/`observeCachedCategories`
 * overrides). Before this fix [FeedViewModel.feeds] / [FeedViewModel.categories] were only
 * ever populated by a successful network call, so the sidebar rendered nothing at all in
 * this scenario — only the all-articles view worked. Modeled on `SidebarFeedStatusTest` +
 * `SidebarUnreadBadgeTest`'s DOM-mount style, and on BUG-62's Android-side
 * `SharedFeedRepositoryTest.observePageResolvesFeedTitleFromPrePopulatedFeedStore_...` for
 * the "every network call fails, only the pre-populated store has data" setup.
 */
class SidebarOfflineTest {

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
    // FeedRepository fake: every network call fails; only the cache flows have data.
    // ---------------------------------------------------------------------------

    private class OfflineFakeFeedRepository(
        private val cachedFeeds: Map<Int, FeedMeta>,
        private val cachedCategories: List<Category>,
    ) : FeedRepository {

        override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> = flowOf(emptyList())
        override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> = flowOf(0)
        override fun observeTotalCount(): Flow<Int> = flowOf(0)
        override fun observeCount(filter: ArticleFilter): Flow<Int> = flowOf(0)

        override fun observeCachedFeeds(): Flow<Map<Int, FeedMeta>> = flowOf(cachedFeeds)
        override fun observeCachedCategories(): Flow<List<Category>> = flowOf(cachedCategories)

        override suspend fun getFeeds(): List<Feed> = throw RuntimeException("offline: getFeeds unreachable")
        override suspend fun getCategories(): List<Category> = throw RuntimeException("offline: getCategories unreachable")

        override suspend fun refresh() { throw RuntimeException("offline: refresh unreachable") }
        override suspend fun refreshUpstream(): RefreshResult = throw RuntimeException("offline")
        override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult = throw RuntimeException("offline")
        override suspend fun markAsRead(articleId: Int) {}
        override suspend fun markAsUnread(articleId: Int) {}
        override suspend fun markAllAsRead() {}
        override suspend fun markFeedAsRead(feedId: Int) {}
        override suspend fun markArticlesAsRead(articleIds: List<Int>) {}
        override suspend fun markArticlesAsUnread(articleIds: List<Int>) {}
        override suspend fun addFeed(url: String): FeedAddResponse = throw RuntimeException("offline")
        override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
        override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {}
        override suspend fun deleteFeed(feedId: Int) {}
        override suspend fun createCategory(name: String): Int = throw RuntimeException("offline")
        override suspend fun renameCategory(categoryId: Int, newName: String) {}
        override suspend fun deleteCategory(categoryId: Int, reassignTo: Int?) {}
        override suspend fun reorderCategories(orderedCategoryIds: List<Int>) {}
        override suspend fun reorderFeeds(orderedFeedIds: List<Int>) {}
        override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
        override suspend fun importOpml(opmlText: String): OpmlImportResult = throw RuntimeException("offline")
        override suspend fun getServerVersion(): String = throw RuntimeException("offline")
        override suspend fun getParseError(feedId: Int): FeedParseError? = null
        override suspend fun clearArticles() {}
        override suspend fun getRetention(): Int? = null
        override suspend fun setRetention(days: Int?) {}
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun feedMeta(
        id: Int,
        title: String,
        categoryId: Int? = null,
        errorCount: Int = 0,
        serverFeedStatus: String? = null,
    ) = FeedMeta(
        id = id,
        url = "https://example.com/feed/$id",
        title = title,
        customTitle = null,
        categoryId = categoryId,
        isPaused = false,
        errorCount = errorCount,
        serverFeedStatus = serverFeedStatus,
        severity = null,
    )

    private fun makeViewModel(
        repo: FeedRepository,
        coroutineScope: CoroutineScope = CoroutineScope(Job()),
    ): FeedViewModel {
        val settings: Settings = StubSettings()
        return FeedViewModel(
            repository = repo,
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
     * The core BUG-63 part 2 regression: with every API call failing, the sidebar must
     * still render feed rows grouped under their categories (plus an uncategorized one),
     * seeded entirely from the pre-populated store.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun sidebarRendersFeedRowsAndCategoryGroupingFromThePreCachedStoreWhenTheApiIsAllFailing(): dynamic = GlobalScope.promise {
        val cachedFeeds = mapOf(
            10 to feedMeta(10, "Field Notes", categoryId = 1),
            11 to feedMeta(11, "Cold Take", categoryId = 1),
            20 to feedMeta(20, "The Loop", categoryId = 2),
            30 to feedMeta(30, "Orphan", categoryId = null),
        )
        val cachedCategories = listOf(Category(id = 1, name = "Craft", position = 0), Category(id = 2, name = "Tech", position = 1))
        val repo = OfflineFakeFeedRepository(cachedFeeds, cachedCategories)
        val scope = CoroutineScope(Job())
        val vm = makeViewModel(repo, scope)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        renderSidebar(host, vm) // calls vm.loadFeeds()/vm.loadCategories() internally, both of which fail
        repeat(20) { yield() }

        val rows = host.querySelectorAll("[data-feed-item]")
        val ids = (0 until rows.length).map { (rows.item(it) as HTMLElement).getAttribute("data-feed-item") }
        assertEquals(setOf("10", "11", "20", "30"), ids.toSet(), "every cached feed must render even though every API call failed")

        val categoryHeaders = host.querySelectorAll("[data-category-header]")
        val headerIds = (0 until categoryHeaders.length).map { (categoryHeaders.item(it) as HTMLElement).getAttribute("data-category-header") }
        assertEquals(setOf("1", "2"), headerIds.toSet(), "cached categories must render as folder headers")

        host.remove()
        scope.cancel()
    }

    /**
     * The chosen stale-state affordance (BUG-63 part 2): a row seeded from the cache must
     * suppress its health/error badge, even when the cached errorCount/serverFeedStatus
     * would normally show one — that data is a snapshot from whenever the cache was last
     * written, not a live read, and presenting it as current would be exactly the "stale
     * badge reads as live" failure mode BUGS.md calls out.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun staleCachedRowsSuppressTheErrorBadgeDespiteACachedErrorCount(): dynamic = GlobalScope.promise {
        val cachedFeeds = mapOf(10 to feedMeta(10, "Dying Feed", errorCount = 9, serverFeedStatus = "dead"))
        val repo = OfflineFakeFeedRepository(cachedFeeds, emptyList())
        val scope = CoroutineScope(Job())
        val vm = makeViewModel(repo, scope)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)

        renderSidebar(host, vm)
        repeat(20) { yield() }

        val row = host.querySelector("[data-feed-item='10']") as? HTMLElement
        assertNotNull(row, "the cached feed must still render")
        assertEquals("true", row.getAttribute("data-feed-stale"), "a cache-seeded row must be flagged stale")
        assertNull(
            row.querySelector("[data-part='error-badge']"),
            "the error badge must be suppressed on a stale row, even though the cached errorCount/serverFeedStatus indicate dead",
        )

        host.remove()
        scope.cancel()
    }

    /** Sanity check: the same cached feed, rendered once loadFeeds() has succeeded (stale=false), does show its badge. */
    @Test
    fun feedRow_nonStaleRowWithAnErrorDoesShowTheBadge() {
        val feed = FeedUiItem(
            id = 10,
            displayTitle = "Dying Feed",
            rawCustomTitle = null,
            url = "https://example.com/feed/10",
            unreadCount = 0,
            isPaused = false,
            errorCount = 9,
            fetchIntervalMinutes = 60,
            serverFeedStatus = "dead",
            stale = false,
        )
        val host = document.createElement("div") as HTMLElement
        host.append { feedRow(feed, isSelected = false) }

        val badge = host.querySelector("[data-feed-item='10'] [data-part='error-badge']")
        assertNotNull(badge, "a live (non-stale) row with a real error must still show the badge")
    }
}
