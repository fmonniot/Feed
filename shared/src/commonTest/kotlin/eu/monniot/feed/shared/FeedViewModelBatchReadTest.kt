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
     * Records the completion order of the read/unread batches so a test can assert
     * one direction waits for the other's in-flight batch rather than interleaving.
     *
     * The delays are per-direction and configurable because the two coordination
     * tests need *opposite* timings to be genuine pins: whichever batch is fired
     * *first* must be the slow one, so that — absent coordination — the second batch
     * would overtake it and record out of order. A single fixed pair (e.g. read=50,
     * unread=0) only pins the direction whose first batch is the slow one; the mirror
     * direction then passes even on uncoordinated code (the second batch, being the
     * slow one, finishes last anyway). See each test for the timing it selects.
     */
    private class OrderingRepository(
        private val readDelayMs: Long = 50,
        private val unreadDelayMs: Long = 0,
    ) : FakeFeedRepository() {
        val completions = mutableListOf<String>()

        override suspend fun markArticlesAsRead(articleIds: List<Int>) {
            kotlinx.coroutines.delay(readDelayMs)
            completions += "read"
        }

        override suspend fun markArticlesAsUnread(articleIds: List<Int>) {
            kotlinx.coroutines.delay(unreadDelayMs)
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
        // Read (fired first) is the slow one; the fast undo fired right after would
        // overtake it and record "unread" first without the join.
        val repo = OrderingRepository(readDelayMs = 50, unreadDelayMs = 0)
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
    fun markArticlesAsRead_joinsInFlightUnreadBatch() = runTest {
        // BUG-55: the reverse direction — a mark-read batch fired while an
        // unread/undo batch is still in flight — used to have no coordination
        // at all (markAllJob was only ever assigned by the read direction), so
        // it could interleave with the in-flight undo on the same ids instead
        // of waiting for it.
        //
        // Here the unread batch (fired first) must be the slow one so this test is a
        // genuine pin: with the pre-fix code the read batch, fired right after, joins
        // nothing and — being faster — records "read" first, yielding [read, unread]
        // and failing the assertion. The fix's join forces [unread, read]. (With a
        // fast unread batch the assertion would hold even pre-fix, since the unread
        // batch enqueued first would run to completion before the read task started.)
        val repo = OrderingRepository(readDelayMs = 50, unreadDelayMs = 100)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.markArticlesAsUnread(listOf("1", "2"))
        vm.markArticlesAsRead(listOf("1", "2")) // read fired while the undo batch is mid-flight
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("unread", "read"), repo.completions,
            "read must complete after the in-flight undo batch, not interleaved with it",
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
