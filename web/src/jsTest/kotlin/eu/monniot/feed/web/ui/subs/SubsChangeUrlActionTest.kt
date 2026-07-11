package eu.monniot.feed.web.ui.subs

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
// Fake repository that records updateFeedUrl calls
// ---------------------------------------------------------------------------

private class ChangeUrlFakeFeedRepository(private val feedList: List<Feed>) : FeedRepository {

    /** Records every `(feedId, newUrl)` pair passed to [updateFeedUrl]. */
    val updateFeedUrlCalls = mutableListOf<Pair<Int, String>>()

    /** When set, [updateFeedUrl] throws this instead of recording success. */
    var updateFeedUrlFailure: Throwable? = null

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> = flowOf(emptyList())
    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> = flowOf(0)
    override fun observeTotalCount(): Flow<Int> = flowOf(0)
    override fun observeCount(filter: ArticleFilter): Flow<Int> = flowOf(0)

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

    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {
        updateFeedUrlFailure?.let { throw it }
        updateFeedUrlCalls += feedId to newUrl
    }

    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun createCategory(name: String): Int = 0
    override suspend fun renameCategory(categoryId: Int, newName: String) {}
    override suspend fun deleteCategory(categoryId: Int, reassignTo: Int?) {}
    override suspend fun reorderCategories(orderedCategoryIds: List<Int>) {}
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

private fun makeFeedApiItem(id: Int, url: String) = Feed(
    id = id,
    url = url,
    title = "Feed $id",
    custom_title = null,
    is_paused = false,
    fetch_interval_minutes = 60,
    error_count = 0,
    last_fetched = null,
    unread_count = 0,
    category_id = null,
)

private fun makeViewModel(
    repo: ChangeUrlFakeFeedRepository,
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

/**
 * BUG-56: verifies the web "Change URL" overflow menu action end-to-end —
 * from [handleOverflowAction] dispatch, through the (shared) [showFixUrlDialog],
 * to [FeedViewModel.updateFeedUrl] actually reaching the repository with the
 * new URL, and the dialog closing on success / staying open with an inline
 * error on failure.
 *
 * Before this fix, the overflow menu had no "change-url" action at all — the
 * only URL editor lived inside the broken-feed accordion, so a healthy feed's
 * source URL could never be changed from the Subscriptions screen.
 */
class SubsChangeUrlActionTest {

    @AfterTest
    fun cleanup() {
        document.querySelector("[data-fixurl-dialog]")?.let { it.parentNode?.removeChild(it) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun changeUrlAction_opensDialogPrefilledWithCurrentUrl(): dynamic = GlobalScope.promise {
        val repo = ChangeUrlFakeFeedRepository(listOf(makeFeedApiItem(1, "https://old.example.com/feed")))
        val vm = makeViewModel(repo)
        vm.loadFeeds()
        repeat(10) { yield() }

        handleOverflowAction("change-url", 1, vm)

        val dialog = document.querySelector("[data-fixurl-dialog='1']") as? HTMLElement
        assertNotNull(dialog, "Change URL dialog must open")
        val titleEl = dialog.querySelector("[data-fixurl-title]") as? HTMLElement
        assertEquals(
            "Change feed URL", titleEl?.textContent,
            "overflow-menu path must not reuse the broken-feed 'Fix feed URL' title",
        )
        val input = dialog.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input)
        assertEquals("https://old.example.com/feed", input.value, "dialog must be pre-filled with the feed's current URL")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun changeUrlAction_savingCallsRepositoryUpdateFeedUrlAndClosesDialog(): dynamic = GlobalScope.promise {
        val repo = ChangeUrlFakeFeedRepository(listOf(makeFeedApiItem(7, "https://old.example.com/feed")))
        val vm = makeViewModel(repo)
        vm.loadFeeds()
        repeat(10) { yield() }

        handleOverflowAction("change-url", 7, vm)

        val input = document.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input)
        input.value = "https://new.example.com/feed"

        val saveBtn = document.querySelector("[data-fixurl-save]") as? HTMLElement
        assertNotNull(saveBtn)
        saveBtn.click()
        repeat(10) { yield() }

        assertEquals(listOf(7 to "https://new.example.com/feed"), repo.updateFeedUrlCalls)
        assertNull(document.querySelector("[data-fixurl-dialog]"), "dialog must close after a successful update")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun changeUrlAction_showsInlineErrorAndKeepsDialogOpenOnFailure(): dynamic = GlobalScope.promise {
        val repo = ChangeUrlFakeFeedRepository(listOf(makeFeedApiItem(3, "https://old.example.com/feed")))
        // A generic (non-Ktor) exception exercises FeedViewModel.updateFeedUrl's
        // catch-all branch, which maps any non-ClientRequestException failure to
        // "Cannot reach server" and calls onError — same contract as the
        // broken-feed accordion's Fix URL action.
        repo.updateFeedUrlFailure = RuntimeException("boom")
        val vm = makeViewModel(repo)
        vm.loadFeeds()
        repeat(10) { yield() }

        handleOverflowAction("change-url", 3, vm)

        val input = document.querySelector("[data-fixurl-input]") as? HTMLInputElement
        assertNotNull(input)
        input.value = "https://bad.example.com"

        val saveBtn = document.querySelector("[data-fixurl-save]") as? HTMLElement
        assertNotNull(saveBtn)
        saveBtn.click()
        repeat(10) { yield() }

        val dialog = document.querySelector("[data-fixurl-dialog='3']") as? HTMLElement
        assertNotNull(dialog, "dialog must stay open on error")
        val errorEl = dialog.querySelector("[data-fixurl-error]") as? HTMLElement
        assertNotNull(errorEl)
        assertEquals("block", errorEl.style.display)
    }
}
