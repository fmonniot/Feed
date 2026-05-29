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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SyncTestSettings : Settings {
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

private fun failingRepo(): FeedRepository = object : FeedRepository {
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

class FeedViewModelSyncStateTest {

    private fun makeVm(repo: FeedRepository, scope: CoroutineScope): FeedViewModel {
        val settings: Settings = SyncTestSettings()
        return FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(SyncTestSettings()),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
    }

    @Test
    fun lastSyncTimeStartsNull() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertNull(vm.lastSyncTime.value, "lastSyncTime must be null before any refresh")
        vm.close()
    }

    @Test
    fun syncFailedStartsFalse() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertFalse(vm.syncFailed.value, "syncFailed must be false before any refresh")
        vm.close()
    }

    @Test
    fun lastSyncTimeSetAfterSuccessfulRefresh() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertNotNull(vm.lastSyncTime.value, "lastSyncTime must be set after a successful refresh")
        vm.close()
    }

    @Test
    fun syncFailedFalseAfterSuccessfulRefresh() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.syncFailed.value, "syncFailed must remain false after a successful refresh")
        vm.close()
    }

    @Test
    fun syncFailedTrueAfterRefreshThrows() = runTest {
        val vm = makeVm(failingRepo(), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.syncFailed.value, "syncFailed must be true when refresh() throws")
        vm.close()
    }

    @Test
    fun lastSyncTimeNotUpdatedAfterRefreshThrows() = runTest {
        val vm = makeVm(failingRepo(), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertNull(vm.lastSyncTime.value, "lastSyncTime must stay null when refresh() throws")
        vm.close()
    }

    @Test
    fun syncFailedResetAfterRetry() = runTest {
        // First call fails, second succeeds — syncFailed must return to false.
        var shouldFail = true
        val mixedRepo = object : FeedRepository {
            override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
            override suspend fun refresh() { if (shouldFail) throw RuntimeException("network error") }
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
        val vm = makeVm(mixedRepo, CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.syncFailed.value, "precondition: syncFailed is true after first failing refresh")
        shouldFail = false
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.syncFailed.value, "syncFailed must reset to false after a successful retry")
        vm.close()
    }

    // ── consecutiveFailures / serverUnreachable tests ─────────────────────────

    @Test
    fun consecutiveFailuresStartsAtZero() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertEquals(0, vm.consecutiveFailures.value, "consecutiveFailures must start at 0")
        vm.close()
    }

    @Test
    fun serverUnreachableStartsFalse() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertFalse(vm.serverUnreachable.value, "serverUnreachable must be false initially")
        vm.close()
    }

    @Test
    fun consecutiveFailuresIncrementOnEachFailure() = runTest {
        val vm = makeVm(failingRepo(), CoroutineScope(coroutineContext + Job()))
        vm.refresh(); testScheduler.advanceUntilIdle()
        vm.refresh(); testScheduler.advanceUntilIdle()
        assertEquals(2, vm.consecutiveFailures.value, "consecutiveFailures must be 2 after two failures")
        vm.close()
    }

    @Test
    fun serverUnreachableTrueAfterThreeConsecutiveFailures() = runTest {
        val vm = makeVm(failingRepo(), CoroutineScope(coroutineContext + Job()))
        repeat(3) { vm.refresh(); testScheduler.advanceUntilIdle() }
        assertTrue(vm.serverUnreachable.value, "serverUnreachable must be true after 3 consecutive failures")
        vm.close()
    }

    @Test
    fun consecutiveFailuresResetOnSuccess() = runTest {
        var shouldFail = true
        val mixedRepo = object : FeedRepository {
            override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
            override suspend fun refresh() { if (shouldFail) throw RuntimeException("server down") }
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
        val vm = makeVm(mixedRepo, CoroutineScope(coroutineContext + Job()))
        repeat(3) { vm.refresh(); testScheduler.advanceUntilIdle() }
        assertTrue(vm.serverUnreachable.value, "precondition: serverUnreachable after 3 failures")
        shouldFail = false
        vm.refresh(); testScheduler.advanceUntilIdle()
        assertEquals(0, vm.consecutiveFailures.value, "consecutiveFailures must reset to 0 after success")
        assertFalse(vm.serverUnreachable.value, "serverUnreachable must reset to false after successful refresh")
        vm.close()
    }

    // ── isOffline tests ───────────────────────────────────────────────────────

    @Test
    fun isOfflineStartsFalse() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertFalse(vm.isOffline.value, "isOffline must be false before any refresh")
        vm.close()
    }

    @Test
    fun isOfflineTrueAfterNonHttpFailure() = runTest {
        val vm = makeVm(failingRepo(), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.isOffline.value, "isOffline must be true when refresh() throws a non-HTTP exception")
        vm.close()
    }

    @Test
    fun isOfflineResetAfterSuccessfulRefresh() = runTest {
        var shouldFail = true
        val mixedRepo = object : FeedRepository {
            override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
            override suspend fun refresh() { if (shouldFail) throw RuntimeException("no network") }
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
        val vm = makeVm(mixedRepo, CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.isOffline.value, "precondition: isOffline is true after network failure")
        shouldFail = false
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.isOffline.value, "isOffline must reset to false after a successful refresh")
        vm.close()
    }

    // ── concurrent-refresh short-circuit (F9) ─────────────────────────────────

    @Test
    fun concurrentRefreshShortCircuitsSecondCall() = runTest {
        // F9: two parallel failing refreshes must not under-count _consecutiveFailures.
        // The first refresh sets _isRefreshing=true and suspends in repository.refresh();
        // the second refresh() call observes that and short-circuits before launching,
        // so only one network call happens and consecutiveFailures lands at exactly 1.
        val gate = CompletableDeferred<Unit>()
        var refreshCalls = 0
        val gatedFailingRepo = object : FeedRepository {
            override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
            override suspend fun refresh() {
                refreshCalls++
                gate.await()
                throw RuntimeException("network error")
            }
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
        val vm = makeVm(gatedFailingRepo, CoroutineScope(coroutineContext + Job()))

        // First refresh: run its body until it suspends inside repository.refresh().
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.isRefreshing.value, "precondition: first refresh is in flight")
        assertEquals(1, refreshCalls, "precondition: exactly one network call so far")

        // Second refresh while the first is in flight: must short-circuit, no new launch.
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(1, refreshCalls, "second refresh() must short-circuit — only one network call total")

        // Release the gate so the first refresh completes (and fails).
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.consecutiveFailures.value, "only one failure should be counted (no race)")
        assertFalse(vm.isRefreshing.value, "isRefreshing must clear after the in-flight refresh completes")
        vm.close()
    }
}
