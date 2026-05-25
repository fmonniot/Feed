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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ── Minimal test settings ─────────────────────────────────────────────────────

private class RateLimitTestSettings : Settings {
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

private fun makeVm(repo: FeedRepository, scope: CoroutineScope): FeedViewModel {
    val settings: Settings = RateLimitTestSettings()
    return FeedViewModel(
        repository = repo,
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(RateLimitTestSettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = scope,
    )
}

/** Throws RateLimitException directly — no Ktor mock needed. */
private fun rateLimitedRepo(retryAfterSeconds: Long = 60L): FeedRepository = object : FeedRepository {
    override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
    override suspend fun refresh() { throw RateLimitException(retryAfterSeconds) }
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = error("")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult = error("")
    override suspend fun getServerVersion(): String = error("")
    override suspend fun getParseError(feedId: Int): eu.monniot.feed.shared.api.FeedParseError? = null
    override suspend fun clearArticles() {}
}

private fun okRepo(): FeedRepository = object : FeedRepository {
    override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
    override suspend fun refresh() {}
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = error("")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = emptyList()
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult = error("")
    override suspend fun getServerVersion(): String = error("")
    override suspend fun getParseError(feedId: Int): eu.monniot.feed.shared.api.FeedParseError? = null
    override suspend fun clearArticles() {}
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class FeedViewModelRateLimitTest {

    @Test
    fun rateLimitedUntilNullInitially() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertNull(vm.rateLimitedUntil.value, "rateLimitedUntil must be null before any refresh")
        vm.close()
    }

    @Test
    fun rateLimitDurationNullInitially() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertNull(vm.rateLimitDuration.value, "rateLimitDuration must be null before any refresh")
        vm.close()
    }

    // runCurrent() runs ready coroutines without advancing virtual time, so the
    // rate-limit delay coroutine suspends at delay() rather than completing and
    // clearing the state before we can assert on it.

    @Test
    fun rateLimitedUntilSetAfterRateLimitException() = runTest {
        val vm = makeVm(rateLimitedRepo(120L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertNotNull(vm.rateLimitedUntil.value, "rateLimitedUntil must be set after a rate-limit response")
        vm.close()
    }

    @Test
    fun rateLimitDurationFormattedAsMinutes() = runTest {
        val vm = makeVm(rateLimitedRepo(120L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertEquals("2m", vm.rateLimitDuration.value, "120 seconds should format as '2m'")
        vm.close()
    }

    @Test
    fun rateLimitDurationFormattedAsSeconds() = runTest {
        val vm = makeVm(rateLimitedRepo(30L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertEquals("30s", vm.rateLimitDuration.value, "30 seconds should format as '30s'")
        vm.close()
    }

    @Test
    fun rateLimitClearsAfterDelay() = runTest {
        val vm = makeVm(rateLimitedRepo(60L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertNotNull(vm.rateLimitedUntil.value, "precondition: rate limit is set")
        testScheduler.advanceTimeBy(61_000L) // past the 60-second delay
        testScheduler.runCurrent()           // run the delay completion block
        assertNull(vm.rateLimitedUntil.value, "rateLimitedUntil must clear after the Retry-After delay elapses")
        assertNull(vm.rateLimitDuration.value, "rateLimitDuration must clear after the delay elapses")
        vm.close()
    }

    @Test
    fun normalFailureDoesNotSetRateLimit() = runTest {
        val failRepo = object : FeedRepository {
            override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
            override suspend fun refresh() { throw RuntimeException("network error") }
            override suspend fun markAsRead(articleId: Int) {}
            override suspend fun markAsUnread(articleId: Int) {}
            override suspend fun getFeeds(): List<Feed> = emptyList()
            override suspend fun addFeed(url: String): FeedAddResponse = error("")
            override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
            override suspend fun deleteFeed(feedId: Int) {}
            override suspend fun getCategories(): List<Category> = emptyList()
            override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
            override suspend fun importOpml(opmlText: String): OpmlImportResult = error("")
            override suspend fun getServerVersion(): String = error("")
            override suspend fun getParseError(feedId: Int): eu.monniot.feed.shared.api.FeedParseError? = null
            override suspend fun clearArticles() {}
        }
        val vm = makeVm(failRepo, CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertNull(vm.rateLimitedUntil.value, "non-429 failure must not set rateLimitedUntil")
        assertNull(vm.rateLimitDuration.value, "non-429 failure must not set rateLimitDuration")
        vm.close()
    }

    @Test
    fun rateLimitDoesNotIncrementConsecutiveFailures() = runTest {
        val vm = makeVm(rateLimitedRepo(60L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertEquals(0, vm.consecutiveFailures.value, "429 must not increment consecutiveFailures")
        vm.close()
    }
}
