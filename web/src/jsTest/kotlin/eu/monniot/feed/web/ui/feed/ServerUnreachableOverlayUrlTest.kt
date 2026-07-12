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
import eu.monniot.feed.web.isOffline
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
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Inline test doubles (duplicated per-file, following the pattern already
// established in MountScopeCancellationTest.kt / LoginServerUrlIntegrationTest.kt
// — the web test module cannot access shared/commonTest fakes).
// ---------------------------------------------------------------------------

private class ServerUnreachableInMemorySettings : Settings {
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

/** Always fails `refresh()`, letting a test drive `consecutiveFailures` past the ERR-5 threshold. */
private class ServerUnreachableAlwaysFailingRepository : FeedRepository {
    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        MutableStateFlow(emptyList())
    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> = MutableStateFlow(0)
    override fun observeTotalCount(): Flow<Int> = MutableStateFlow(0)
    override fun observeCount(filter: ArticleFilter): Flow<Int> = MutableStateFlow(0)
    override suspend fun refresh() { throw RuntimeException("simulated unreachable server") }
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

private fun serverUnreachableMakeHost(): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    return host
}

/**
 * BUG-49: the ERR-5 server-unreachable overlay used to read
 * `viewModel.serverUrl.value` at render time. `serverUrl` is a
 * `WhileSubscribed(5000)` `StateFlow` — without an active collector its
 * `.value` stays pinned at the value it was seeded with at ViewModel
 * construction, silently going stale if the URL changes while the overlay is
 * showing. The fix folds `viewModel.serverUrl` into the same `combine(...)`
 * that already drives the overlay's visibility, so the render path always
 * observes the current value via an active collector.
 *
 * This test forces `consecutiveFailures` past the ERR-5 threshold (3) so the
 * overlay is showing, then changes the server URL through [ServerUrlStore]
 * — the same store `FeedViewModel.serverUrl` is derived from — and asserts
 * the already-visible overlay picks up the new URL without needing a remount.
 */
class ServerUnreachableOverlayUrlTest {

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun overlayReflectsServerUrlChangeWhileAlreadyShowing(): dynamic = GlobalScope.promise {
        val previousOffline = isOffline.value
        isOffline.value = false

        val settings: Settings = ServerUnreachableInMemorySettings()
        val serverUrlStore = ServerUrlStore(settings)
        val scope = CoroutineScope(Job())
        val vm = FeedViewModel(
            repository = ServerUnreachableAlwaysFailingRepository(),
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(ServerUnreachableInMemorySettings()),
            clearCookies = {},
            serverUrlStore = serverUrlStore,
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
        val host = serverUnreachableMakeHost()
        try {
            renderFeedScreen(host, vm)
            // renderFeedScreen's own mount triggers one syncFromServer() call;
            // force two more failures to cross the >= 3 threshold that flips
            // serverUnreachable and shows the overlay.
            repeat(10) { yield() }
            vm.syncFromServer()
            repeat(10) { yield() }
            vm.syncFromServer()
            repeat(10) { yield() }

            val overlay = host.querySelector("#feed-screen-content-overlay") as? HTMLElement
            assertNotNull(overlay, "server-unreachable overlay container must exist")
            assertTrue(overlay.style.display == "block", "overlay must be visible once serverUnreachable flips true")

            val monoBefore = overlay.querySelector("[data-part='mono']")
            assertNotNull(monoBefore, "mono block must render the server URL")
            assertTrue(
                monoBefore.textContent?.contains(ServerUrlStore.DEFAULT) == true,
                "overlay must initially show the seeded server URL, was: ${monoBefore.textContent}",
            )

            // Change the URL through the store while the overlay is already
            // showing. Pre-fix this had no effect on the overlay: nothing
            // collected `viewModel.serverUrl`, so its `.value` stayed pinned
            // at the ViewModel-construction-time seed.
            val changedUrl = serverUrlStore.setUrl("http://changed.example.test:4000")
            assertNotNull(changedUrl, "setUrl must normalize the new URL successfully")
            repeat(10) { yield() }

            val monoAfter = overlay.querySelector("[data-part='mono']")
            assertNotNull(monoAfter, "mono block must still exist after the URL change")
            assertTrue(
                monoAfter.textContent?.contains(changedUrl) == true,
                "overlay must reflect the newly changed server URL, was: ${monoAfter.textContent}",
            )
            // Pin the actual BUG-49 invariant: the stale URL is *replaced*, not
            // merely joined by the new one. This would fail if render() ever
            // regressed from replace to append semantics and the overlay
            // accumulated both the seeded and the changed URL.
            assertTrue(
                monoAfter.textContent?.contains(ServerUrlStore.DEFAULT) == false,
                "overlay must no longer show the stale seeded URL, was: ${monoAfter.textContent}",
            )
        } finally {
            host.remove()
            scope.cancel()
            isOffline.value = previousOffline
        }
    }

    /**
     * The "N consecutive failures" line stays live while the overlay is already
     * showing. `serverUnreachable` dedups once it flips `true`, so before the
     * fix the overlay's `combine(...)` never re-emitted on a failure that
     * happened after the overlay appeared — the displayed count froze at the
     * threshold (3) no matter how many retries kept failing. Folding
     * `viewModel.consecutiveFailures` into that same `combine` makes the count
     * re-render on every increment. This test crosses the threshold, then
     * forces one more failure and asserts the count advances without a remount.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun overlayReflectsConsecutiveFailuresWhileAlreadyShowing(): dynamic = GlobalScope.promise {
        val previousOffline = isOffline.value
        isOffline.value = false

        val settings: Settings = ServerUnreachableInMemorySettings()
        val serverUrlStore = ServerUrlStore(settings)
        val scope = CoroutineScope(Job())
        val vm = FeedViewModel(
            repository = ServerUnreachableAlwaysFailingRepository(),
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(ServerUnreachableInMemorySettings()),
            clearCookies = {},
            serverUrlStore = serverUrlStore,
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
        val host = serverUnreachableMakeHost()
        try {
            renderFeedScreen(host, vm)
            // Mount triggers one syncFromServer(); two more cross the >= 3
            // threshold that flips serverUnreachable and shows the overlay.
            repeat(10) { yield() }
            vm.syncFromServer()
            repeat(10) { yield() }
            vm.syncFromServer()
            repeat(10) { yield() }

            val overlay = host.querySelector("#feed-screen-content-overlay") as? HTMLElement
            assertNotNull(overlay, "server-unreachable overlay container must exist")
            assertTrue(overlay.style.display == "block", "overlay must be visible once serverUnreachable flips true")

            val monoBefore = overlay.querySelector("[data-part='mono']")
            assertNotNull(monoBefore, "mono block must render the failure count")
            assertTrue(
                monoBefore.textContent?.contains("failures: 3 consecutive") == true,
                "overlay must show the threshold failure count (3), was: ${monoBefore.textContent}",
            )

            // One more failure while the overlay is already showing. Pre-fix the
            // combine never re-emitted (serverUnreachable dedups at true), so the
            // displayed count stayed frozen at 3.
            vm.syncFromServer()
            repeat(10) { yield() }

            val monoAfter = overlay.querySelector("[data-part='mono']")
            assertNotNull(monoAfter, "mono block must still exist after another failure")
            assertTrue(
                monoAfter.textContent?.contains("failures: 4 consecutive") == true,
                "overlay must reflect the incremented failure count (4) without a remount, was: ${monoAfter.textContent}",
            )
        } finally {
            host.remove()
            scope.cancel()
            isOffline.value = previousOffline
        }
    }
}
