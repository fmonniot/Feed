package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private class AddFeedTestSettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun hasKey(key: String) = key in map
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

/** Returns a real [ClientRequestException] with [status] via a MockEngine round-trip. */
private suspend fun buildException(status: HttpStatusCode): ClientRequestException {
    val engine = MockEngine { respond("", status) }
    val client = HttpClient(engine) { expectSuccess = false }
    val response = client.get("http://test/")
    return ClientRequestException(response, "")
}

/**
 * A minimal FeedRepository that:
 * - Returns [feedsToReturn] from [getFeeds]
 * - Throws [addFeedException] (if set) from [addFeed], or returns success
 * - Tracks how many times [addFeed] was called
 */
private open class TestRepo(
    private val feedsToReturn: List<Feed> = emptyList(),
    private val addFeedException: Exception? = null,
) : FeedRepository {
    var addFeedCallCount = 0

    override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
    override suspend fun refresh() {}
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> = feedsToReturn
    override suspend fun addFeed(url: String): FeedAddResponse {
        addFeedCallCount++
        if (addFeedException != null) throw addFeedException
        return FeedAddResponse(id = 99, message = "ok")
    }
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult = error("")
    override suspend fun getServerVersion(): String = "0.0.0"
    override suspend fun getParseError(feedId: Int): eu.monniot.feed.shared.api.FeedParseError? = null
    override suspend fun clearArticles() {}
}

private fun makeVm(repo: FeedRepository, scope: CoroutineScope): FeedViewModel {
    val settings: Settings = AddFeedTestSettings()
    return FeedViewModel(
        repository = repo,
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(AddFeedTestSettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = scope,
    )
}

private fun makeFeed(id: Int, url: String, title: String = "Feed $id", categoryId: Int? = null) = Feed(
    id = id,
    url = url,
    title = title,
    custom_title = null,
    is_paused = false,
    fetch_interval_minutes = 60,
    error_count = 0,
    last_fetched = null,
    unread_count = 0,
    category_id = categoryId,
)

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/**
 * ERR-12 / ERR-13: Validates that [FeedViewModel.addFeed] produces the correct
 * [AddFeedError] states without sending a server request when appropriate.
 */
class FeedViewModelAddFeedValidationTest {

    // -------------------------------------------------------------------------
    // ERR-13: duplicate URL → Duplicate error, no server request
    // -------------------------------------------------------------------------

    @Test
    fun duplicateUrl_setsDuplicateError_noServerCall() = runTest {
        val existingFeed = makeFeed(id = 1, url = "https://existing.example.com/feed")
        val repo = TestRepo(feedsToReturn = listOf(existingFeed))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Pre-populate feeds by calling loadFeeds()
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        // Now try to add the same URL
        vm.addFeed("https://existing.example.com/feed") {}
        testScheduler.advanceUntilIdle()

        val err = vm.addFeedError.value
        assertIs<AddFeedError.Duplicate>(err, "Expected Duplicate error, got $err")
        assertEquals(1, err.feedId)
        assertEquals("Feed 1", err.feedName)
        assertNull(err.folderName)

        // No POST /v1/feeds was sent
        assertEquals(0, repo.addFeedCallCount, "addFeed() must not be called for duplicate URL")
        vm.close()
    }

    @Test
    fun duplicateUrl_withFolder_includesFolderName() = runTest {
        val existingFeed = makeFeed(id = 5, url = "https://craft.example.com/rss", title = "Craft Blog", categoryId = 10)
        val category = Category(id = 10, name = "Craft", position = 0)
        val repo = object : TestRepo(feedsToReturn = listOf(existingFeed)) {
            override suspend fun getCategories(): List<Category> = listOf(category)
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        vm.loadCategories()
        testScheduler.advanceUntilIdle()

        vm.addFeed("https://craft.example.com/rss") {}
        testScheduler.advanceUntilIdle()

        val err = vm.addFeedError.value
        assertIs<AddFeedError.Duplicate>(err)
        assertEquals("Craft Blog", err.feedName)
        assertEquals("Craft", err.folderName)
        vm.close()
    }

    @Test
    fun duplicateUrl_urlTrimmedBeforeCheck() = runTest {
        val existingFeed = makeFeed(id = 2, url = "https://trimmed.example.com/feed")
        val repo = TestRepo(feedsToReturn = listOf(existingFeed))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        // URL with leading/trailing whitespace should still be detected as duplicate
        vm.addFeed("  https://trimmed.example.com/feed  ") {}
        testScheduler.advanceUntilIdle()

        assertIs<AddFeedError.Duplicate>(vm.addFeedError.value)
        assertEquals(0, repo.addFeedCallCount)
        vm.close()
    }

    // -------------------------------------------------------------------------
    // ERR-12: server 400 response → ParseFail error
    // -------------------------------------------------------------------------

    @Test
    fun serverReturns400_setsParseFail() = runTest {
        val badRequestException = buildException(HttpStatusCode.BadRequest)
        val repo = TestRepo(addFeedException = badRequestException)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        var successCalled = false
        vm.addFeed("https://not-a-feed.example.com") { successCalled = true }
        testScheduler.advanceUntilIdle()

        val err = vm.addFeedError.value
        assertIs<AddFeedError.ParseFail>(err, "Expected ParseFail for 400 response, got $err")
        assertEquals(false, successCalled, "onSuccess must not fire on 400 error")
        vm.close()
    }

    // -------------------------------------------------------------------------
    // Happy path: no duplicate, server succeeds → no error, onSuccess called
    // -------------------------------------------------------------------------

    @Test
    fun happyPath_noError_onSuccessCalled() = runTest {
        val repo = TestRepo(feedsToReturn = emptyList())
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        var successCalled = false
        vm.addFeed("https://new.example.com/feed") { successCalled = true }
        testScheduler.advanceUntilIdle()

        assertNull(vm.addFeedError.value, "No error expected on happy path")
        assertEquals(true, successCalled, "onSuccess must be called on happy path")
        assertEquals(1, repo.addFeedCallCount)
        vm.close()
    }

    @Test
    fun differentUrl_noFalsePositive() = runTest {
        val existingFeed = makeFeed(id = 1, url = "https://existing.example.com/feed")
        val repo = TestRepo(feedsToReturn = listOf(existingFeed))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        var successCalled = false
        vm.addFeed("https://different.example.com/feed") { successCalled = true }
        testScheduler.advanceUntilIdle()

        assertNull(vm.addFeedError.value, "No duplicate error for a different URL")
        assertEquals(true, successCalled)
        assertEquals(1, repo.addFeedCallCount)
        vm.close()
    }
}
