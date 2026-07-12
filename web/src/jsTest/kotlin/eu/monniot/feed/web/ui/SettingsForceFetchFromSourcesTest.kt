package eu.monniot.feed.web.ui

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ── Inline test doubles ──────────────────────────────────────────────────────
// The web test module cannot access shared/commonTest, so we duplicate the
// minimal fakes needed to construct a FeedViewModel (see
// SidebarGlobalCounterTest.kt for the same pattern).

private class ForceFetchInMemorySettings : Settings {
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
 * #129: a [FeedRepository] fake that tracks how many times [refresh] (cheap
 * re-read) vs [refreshUpstream] (`POST /v1/feeds/refresh` fan-out) are called,
 * and lets tests configure the upstream result — used to verify the "Force
 * fetch from sources" Settings action wiring.
 */
private class ForceFetchFakeFeedRepository(
    private val refreshUpstreamBehavior: suspend () -> RefreshResult = { RefreshResult.Success(0) },
) : FeedRepository {
    var refreshCallCount = 0
        private set
    var refreshUpstreamCallCount = 0
        private set

    private val itemsFlow = MutableStateFlow<List<ArticleItem>>(emptyList())

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> = itemsFlow
    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> = MutableStateFlow(0)
    override fun observeTotalCount(): Flow<Int> = MutableStateFlow(0)
    override fun observeCount(filter: ArticleFilter): Flow<Int> = MutableStateFlow(0)

    override suspend fun refresh() { refreshCallCount++ }
    override suspend fun refreshUpstream(): RefreshResult {
        refreshUpstreamCallCount++
        return refreshUpstreamBehavior()
    }
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

private fun makeViewModel(
    repo: FeedRepository,
    coroutineScope: CoroutineScope = CoroutineScope(Job()),
): FeedViewModel {
    val settings: Settings = ForceFetchInMemorySettings()
    return FeedViewModel(
        repository = repo,
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(ForceFetchInMemorySettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}

/**
 * #129: the "Force fetch from sources" Settings action — the explicit,
 * warning-styled escape hatch for the upstream fan-out that the reflexive
 * refresh gesture (pull-to-refresh / sidebar ↻) no longer triggers.
 */
class SettingsForceFetchFromSourcesTest {

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun clickingButtonTriggersUpstreamFanOutAndShowsStartedMessage(): dynamic = GlobalScope.promise {
        val repo = ForceFetchFakeFeedRepository(refreshUpstreamBehavior = { RefreshResult.Success(4) })
        val vm = makeViewModel(repo)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderSettings(host, vm)
            repeat(5) { yield() }

            val button = document.getElementById(SETTINGS_FORCE_FETCH_BTN_ID) as? HTMLButtonElement
            assertNotNull(button, "Force fetch from sources button must be rendered")
            assertEquals("Fetch now", button.textContent, "button must read 'Fetch now' before any attempt")

            button.click()
            repeat(10) { yield() }

            assertEquals(1, repo.refreshUpstreamCallCount, "clicking must trigger the upstream fan-out")
            assertEquals(1, repo.refreshCallCount, "clicking must also trigger the follow-up cheap re-read")
            assertTrue(vm.fetchFromSourcesResult.value.orEmpty().contains("4"), "result message must mention the feed count")
            assertTrue(
                vm.fetchFromSourcesResult.value.orEmpty().contains("Started fetching", ignoreCase = false),
                "post-#182 success is phrased as 'started fetching', not a completion count",
            )
            assertFalse(vm.isFetchingFromSources.value, "progress flag must clear once the fetch completes")

            // The DOM must reflect the result message somewhere in the row's hint text.
            val statusText = document.querySelector("[data-settings-row='Force fetch from sources']")?.textContent
            assertNotNull(statusText)
            assertTrue(statusText.contains("Started fetching 4 sources"), "rendered hint must show the result message, got: $statusText")
        } finally {
            document.body?.removeChild(host)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun revisitingSettingsClearsStaleFetchFromSourcesResult(): dynamic = GlobalScope.promise {
        // The "Force fetch from sources" row has no dismiss affordance of its own —
        // renderSettings() must clear a stale result message on every (re-)mount, the
        // same way it already does for OPML import status/failures.
        val repo = ForceFetchFakeFeedRepository(refreshUpstreamBehavior = { RefreshResult.Success(4) })
        val vm = makeViewModel(repo)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderSettings(host, vm)
            repeat(5) { yield() }

            val button = document.getElementById(SETTINGS_FORCE_FETCH_BTN_ID) as? HTMLButtonElement
            assertNotNull(button)
            button.click()
            repeat(10) { yield() }

            assertTrue(
                vm.fetchFromSourcesResult.value.orEmpty().contains("Started fetching"),
                "precondition: a result message must be set before simulating a revisit",
            )

            // Simulate navigating away and back to the Settings screen.
            renderSettings(host, vm)
            repeat(5) { yield() }

            assertEquals(
                null, vm.fetchFromSourcesResult.value,
                "revisiting Settings must clear a stale result message from a previous visit",
            )
        } finally {
            document.body?.removeChild(host)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun clickingButtonNeverTouchesIsRefreshing(): dynamic = GlobalScope.promise {
        // #129: the Settings action must use its own progress state — it must
        // never drive the sidebar's isRefreshing/"Syncing…" indicator.
        val repo = ForceFetchFakeFeedRepository(refreshUpstreamBehavior = { RefreshResult.Success(1) })
        val vm = makeViewModel(repo)

        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            renderSettings(host, vm)
            repeat(5) { yield() }

            val button = document.getElementById(SETTINGS_FORCE_FETCH_BTN_ID) as? HTMLButtonElement
            assertNotNull(button)
            button.click()
            repeat(10) { yield() }

            assertFalse(vm.isRefreshing.value, "fetchFromSources() must never drive isRefreshing")
        } finally {
            document.body?.removeChild(host)
        }
    }
}
