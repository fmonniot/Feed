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
import kotlinx.coroutines.isActive
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Inline test doubles (duplicated per-file, following the pattern already
// established in ArticleListLoadMoreTest.kt / SidebarUnreadBadgeTest.kt — the
// web test module cannot access shared/commonTest fakes).
// ---------------------------------------------------------------------------

private class MountScopeInMemorySettings : Settings {
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

private class MountScopeFakeFeedRepository(
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

private fun mountScopeMakeViewModel(
    itemsFlow: MutableStateFlow<List<ArticleItem>> = MutableStateFlow(emptyList()),
): FeedViewModel {
    val settings: Settings = MountScopeInMemorySettings()
    return FeedViewModel(
        repository = MountScopeFakeFeedRepository(itemsFlow),
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(MountScopeInMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = CoroutineScope(Job()),
    )
}

private fun mountScopeMakeHost(): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    return host
}

/**
 * BUG-47: `renderArticleList`/`renderSidebar`/`renderReaderPane` used to launch
 * their state-flow collectors on `GlobalScope`, so every remount (e.g.
 * Settings/Subscriptions → Feed navigation, which re-invokes `renderFeedScreen`
 * and its three sub-components) left the previous mount's collectors running
 * forever, stacking up on every navigation. The fix mirrors the
 * `feedScreenScope` pattern already used by `FeedScreen.kt`: each component
 * keeps a module-level mount-scoped `CoroutineScope`, cancelled and replaced at
 * the top of every render call. Verifies the previous mount's scope is
 * cancelled (not merely superseded) on remount.
 */
class MountScopeCancellationTest {

    @Test
    fun remountingArticleListCancelsThePreviousMountScope() {
        val vm = mountScopeMakeViewModel()
        val host = mountScopeMakeHost()
        try {
            renderArticleList(host, vm)
            val firstScope = articleListMountScope
            assertNotNull(firstScope, "renderArticleList must install a mount scope")
            assertTrue(firstScope.isActive, "the freshly installed scope must be active")

            renderArticleList(host, vm)
            val secondScope = articleListMountScope
            assertNotNull(secondScope, "remounting must install a new mount scope")

            assertFalse(firstScope.isActive, "the previous mount's scope must be cancelled on remount")
            assertNotSame(firstScope, secondScope, "remounting must replace the scope, not reuse it")
            assertTrue(secondScope.isActive, "the new mount's scope must be active")
        } finally {
            host.remove()
            articleListMountScope?.cancel()
        }
    }

    @Test
    fun remountingSidebarCancelsThePreviousMountScope() {
        val vm = mountScopeMakeViewModel()
        val host = mountScopeMakeHost()
        try {
            renderSidebar(host, vm)
            val firstScope = sidebarScope
            assertNotNull(firstScope, "renderSidebar must install a mount scope")
            assertTrue(firstScope.isActive, "the freshly installed scope must be active")

            renderSidebar(host, vm)
            val secondScope = sidebarScope
            assertNotNull(secondScope, "remounting must install a new mount scope")

            assertFalse(firstScope.isActive, "the previous mount's scope must be cancelled on remount")
            assertNotSame(firstScope, secondScope, "remounting must replace the scope, not reuse it")
            assertTrue(secondScope.isActive, "the new mount's scope must be active")
        } finally {
            host.remove()
            sidebarScope?.cancel()
        }
    }

    @Test
    fun remountingReaderPaneCancelsThePreviousMountScope() {
        val vm = mountScopeMakeViewModel()
        val host = mountScopeMakeHost()
        try {
            renderReaderPane(host, vm)
            val firstScope = readerPaneScope
            assertNotNull(firstScope, "renderReaderPane must install a mount scope")
            assertTrue(firstScope.isActive, "the freshly installed scope must be active")

            renderReaderPane(host, vm)
            val secondScope = readerPaneScope
            assertNotNull(secondScope, "remounting must install a new mount scope")

            assertFalse(firstScope.isActive, "the previous mount's scope must be cancelled on remount")
            assertNotSame(firstScope, secondScope, "remounting must replace the scope, not reuse it")
            assertTrue(secondScope.isActive, "the new mount's scope must be active")
        } finally {
            host.remove()
            readerPaneScope?.cancel()
        }
    }
}
