package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the ticket #9 batch-read [FeedViewModel] entry points —
 * [FeedViewModel.markAllAsRead] (no-arg), [FeedViewModel.markFeedAsRead], and
 * [FeedViewModel.markArticlesAsRead] — verifying they delegate to the
 * corresponding [FeedRepository] bulk methods and surface errors the same way
 * as the existing per-id mark methods (logged, then routed through
 * [FeedViewModel.onApiError] / [UiState.Error]).
 */
class FeedViewModelBatchReadTest {

    private class RecordingRepository : FakeFeedRepository() {
        var markAllAsReadCalls = 0
        var markFeedAsReadIds = mutableListOf<Int>()
        var markArticlesAsReadIds: List<Int>? = null
        var markArticlesAsUnreadIds: List<Int>? = null
        var boom: Throwable? = null

        override suspend fun markAllAsRead() {
            boom?.let { throw it }
            markAllAsReadCalls++
        }

        override suspend fun markFeedAsRead(feedId: Int) {
            boom?.let { throw it }
            markFeedAsReadIds += feedId
        }

        override suspend fun markArticlesAsRead(articleIds: List<Int>) {
            boom?.let { throw it }
            markArticlesAsReadIds = articleIds
        }

        override suspend fun markArticlesAsUnread(articleIds: List<Int>) {
            boom?.let { throw it }
            markArticlesAsUnreadIds = articleIds
        }
    }

    /**
     * Records the completion order of the read/unread batches so a test can
     * assert the undo waits for the in-flight read batch (finding #3). The read
     * batch suspends on [kotlinx.coroutines.delay] so the undo, launched right
     * after, must [kotlinx.coroutines.Job.join] it rather than interleave.
     */
    private class OrderingRepository : FakeFeedRepository() {
        val completions = mutableListOf<String>()

        override suspend fun markArticlesAsRead(articleIds: List<Int>) {
            kotlinx.coroutines.delay(50)
            completions += "read"
        }

        override suspend fun markArticlesAsUnread(articleIds: List<Int>) {
            completions += "unread"
        }
    }

    private fun makeVm(repo: FeedRepository, scope: CoroutineScope): FeedViewModel {
        val settings: Settings = InMemorySettings()
        return FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = SessionManager(InMemorySettings()),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
    }

    @Test
    fun markAllAsRead_delegatesToRepository() = runTest {
        val repo = RecordingRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markAllAsRead()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.markAllAsReadCalls)
        assertEquals(UiState.Idle, vm.uiState.value)
        vm.close()
    }

    @Test
    fun markAllAsRead_surfacesErrorViaUiState() = runTest {
        val repo = RecordingRepository().apply { boom = RuntimeException("boom") }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markAllAsRead()
        testScheduler.advanceUntilIdle()

        assertEquals(UiState.Error("Failed to mark as read"), vm.uiState.value)
        vm.close()
    }

    @Test
    fun markFeedAsRead_delegatesToRepositoryWithFeedId() = runTest {
        val repo = RecordingRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markFeedAsRead(42)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(42), repo.markFeedAsReadIds)
        assertEquals(UiState.Idle, vm.uiState.value)
        vm.close()
    }

    @Test
    fun markFeedAsRead_surfacesErrorViaUiState() = runTest {
        val repo = RecordingRepository().apply { boom = RuntimeException("boom") }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markFeedAsRead(42)
        testScheduler.advanceUntilIdle()

        assertEquals(UiState.Error("Failed to mark as read"), vm.uiState.value)
        vm.close()
    }

    @Test
    fun markArticlesAsRead_mapsStringIdsToIntsAndDelegates() = runTest {
        val repo = RecordingRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markArticlesAsRead(listOf("1", "2", "3"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), repo.markArticlesAsReadIds)
        assertEquals(UiState.Idle, vm.uiState.value)
        vm.close()
    }

    @Test
    fun markArticlesAsRead_surfacesErrorViaUiState() = runTest {
        val repo = RecordingRepository().apply { boom = RuntimeException("boom") }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markArticlesAsRead(listOf("1"))
        testScheduler.advanceUntilIdle()

        assertEquals(UiState.Error("Failed to mark as read"), vm.uiState.value)
        vm.close()
    }

    // Build a real ClientRequestException via a direct (non-launch) MockEngine call in
    // runTest — mirrors FeedViewModelUnauthorizedTest.buildException.
    private suspend fun buildUnauthorizedException(): ClientRequestException {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val client = HttpClient(engine) { expectSuccess = false }
        val response = client.get("http://test/")
        return ClientRequestException(response, "")
    }

    @Test
    fun markArticlesAsUnread_mapsStringIdsToIntsAndDelegates() = runTest {
        val repo = RecordingRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markArticlesAsUnread(listOf("4", "5"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(4, 5), repo.markArticlesAsUnreadIds)
        assertEquals(UiState.Idle, vm.uiState.value)
        vm.close()
    }

    @Test
    fun markArticlesAsUnread_joinsInFlightReadBatch() = runTest {
        // Finding #3: the multi-select undo must wait for a still-in-flight
        // markArticlesAsRead batch rather than interleaving on the same ids.
        val repo = OrderingRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markArticlesAsRead(listOf("1", "2"))
        vm.markArticlesAsUnread(listOf("1", "2")) // undo fired while the read batch is mid-flight
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("read", "unread"), repo.completions,
            "undo must complete after the in-flight read batch, not interleaved with it",
        )
        vm.close()
    }

    @Test
    fun markAllAsRead_401TriggersSessionExpiredInsteadOfInlineError() = runTest {
        val repo = RecordingRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))
        repo.boom = buildUnauthorizedException()

        vm.markAllAsRead()
        testScheduler.advanceUntilIdle()

        assertEquals(UiState.Idle, vm.uiState.value, "a 401 must not set the inline error state")
        vm.close()
    }
}
