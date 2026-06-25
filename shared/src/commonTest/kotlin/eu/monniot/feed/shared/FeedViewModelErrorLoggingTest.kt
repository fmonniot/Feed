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
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts that the per-action `catch (e: Exception)` branches in [FeedViewModel] route
 * the caught throwable through [Logger] before producing the user-facing message.
 * This is the safety net for #23: a `MissingFieldException` from the JSON layer (or
 * any other dropped exception class) must reach a platform log sink during dev.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelErrorLoggingTest {

    private val captured = mutableListOf<Triple<String, String, Throwable>>()
    private val originalSink = Logger.sink

    init {
        Logger.sink = { tag, msg, t -> captured += Triple(tag, msg, t) }
    }

    @AfterTest
    fun tearDown() {
        Logger.sink = originalSink
    }

    private class ThrowingRepository(
        private val boom: Throwable = RuntimeException("boom")
    ) : FeedRepository {
        override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
        override suspend fun refresh() { throw boom }
        override suspend fun refreshForFeed(feedId: Int) { throw boom }
        override suspend fun refreshUpstream(): eu.monniot.feed.shared.api.RefreshResult { throw boom }
        override suspend fun refreshFeedUpstream(feedId: Int): eu.monniot.feed.shared.api.RefreshResult { throw boom }
        override suspend fun markAsRead(articleId: Int) { throw boom }
        override suspend fun markAsUnread(articleId: Int) { throw boom }
        override suspend fun getFeeds(): List<Feed> { throw boom }
        override suspend fun addFeed(url: String): FeedAddResponse { throw boom }
        override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) { throw boom }
        override suspend fun updateFeedUrl(feedId: Int, newUrl: String) { throw boom }
        override suspend fun deleteFeed(feedId: Int) { throw boom }
        override suspend fun getCategories(): List<Category> { throw boom }
        override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) { throw boom }
        override suspend fun importOpml(opmlText: String): OpmlImportResult { throw boom }
        override suspend fun getServerVersion(): String { throw boom }
        override suspend fun getParseError(feedId: Int): eu.monniot.feed.shared.api.FeedParseError? { throw boom }
        override suspend fun clearArticles() { throw boom }
        override suspend fun getRetention(): Int? { throw boom }
        override suspend fun setRetention(days: Int?) { throw boom }
    }

    private fun makeVm(
        testScope: TestScope,
        repo: FeedRepository = ThrowingRepository(),
    ): FeedViewModel {
        val mockEngine = MockEngine { _ -> respond("", HttpStatusCode.OK) }
        val client = HttpClient(mockEngine)
        val settings: Settings = InMemorySettings()
        val vm = FeedViewModel(
            repository = repo,
            authApi = AuthApi(client),
            sessionManager = SessionManager(),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = CoroutineScope(testScope.coroutineContext + Job()),
        )
        return vm
    }

    @Test
    fun refreshLogsExceptionBeforeMapping() = runTest {
        val vm = makeVm(this)
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(1, captured.size, "expected exactly one log call")
        val (tag, msg, t) = captured.single()
        assertEquals("FeedViewModel", tag)
        assertTrue("refresh" in msg, "log message should name the action: $msg")
        assertEquals("boom", t.message)
        assertEquals(UiState.Error("Could not refresh — showing cached articles"), vm.uiState.value)
        vm.close()
    }

    @Test
    fun markAsReadLogsExceptionBeforeMapping() = runTest {
        val vm = makeVm(this)
        vm.markAsRead("42")
        testScheduler.advanceUntilIdle()
        assertEquals(1, captured.size)
        assertTrue("markAsRead" in captured.single().second)
        assertEquals(UiState.Error("Failed to mark as read"), vm.uiState.value)
        vm.close()
    }

    @Test
    fun markAsUnreadLogsExceptionBeforeMapping() = runTest {
        val vm = makeVm(this)
        vm.markAsUnread("42")
        testScheduler.advanceUntilIdle()
        assertEquals(1, captured.size)
        assertTrue("markAsUnread" in captured.single().second)
        assertEquals(UiState.Error("Failed to mark as unread"), vm.uiState.value)
        vm.close()
    }

    @Test
    fun loadFeedsLogsExceptionBeforeMapping() = runTest {
        val vm = makeVm(this)
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()
        assertEquals(1, captured.size)
        assertTrue("loadFeeds" in captured.single().second)
        assertEquals("Could not load feeds", vm.feedsError.value)
        vm.close()
    }

    @Test
    fun addFeedNonHttpFailureLogsException() = runTest {
        val vm = makeVm(this)
        vm.addFeed("https://example.com/rss") {}
        testScheduler.advanceUntilIdle()
        assertEquals(1, captured.size)
        assertTrue("addFeed" in captured.single().second)
        assertEquals(AddFeedError.Generic("Cannot reach server"), vm.addFeedError.value)
        vm.close()
    }

    @Test
    fun loadCategoriesLogsExceptionBeforeMapping() = runTest {
        val vm = makeVm(this)
        vm.loadCategories()
        testScheduler.advanceUntilIdle()
        assertEquals(1, captured.size)
        assertTrue("loadCategories" in captured.single().second)
        assertEquals(UiState.Error("Could not load categories"), vm.uiState.value)
        vm.close()
    }

    @Test
    fun importOpmlLogsExceptionBeforeMapping() = runTest {
        val vm = makeVm(this)
        vm.importOpml("<opml/>")
        testScheduler.advanceUntilIdle()
        assertEquals(1, captured.size)
        assertTrue("importOpml" in captured.single().second)
        assertEquals(
            "Import failed — check the OPML file and try again.",
            vm.opmlImportStatus.value,
        )
        vm.close()
    }
}
