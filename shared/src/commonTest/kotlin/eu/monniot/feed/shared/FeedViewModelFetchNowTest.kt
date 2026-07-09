package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #129: [FeedViewModel.fetchFromSources] — the explicit "Force fetch from
 * sources" Settings action — does action B (upstream pull) *then* action A
 * (plain re-read). When the upstream pull is rate-limited (429), it must
 * SILENTLY fall back to the plain re-read — no error state, sync time still
 * updates, but [FeedViewModel.fetchFromSourcesResult] gets a "try again
 * shortly" message. (Before #129 this logic lived in the reflexive
 * `refresh()` gesture; it moved here so a full upstream fan-out is no longer
 * triggered by every pull-to-refresh.)
 */
class FeedViewModelFetchNowTest {

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
    fun fetchFromSourcesTriggersUpstreamPullThenReRead() = runTest {
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { RefreshResult.Success(2) },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshUpstreamCallCount, "fetchFromSources must trigger an upstream pull (action B)")
        assertEquals(1, repo.refreshCallCount, "fetchFromSources must re-read the list afterward (action A)")
        assertNotNull(vm.lastSyncTime.value, "sync time must update after a successful fetch")
        assertFalse(vm.syncFailed.value, "syncFailed must be false on success")
        assertEquals(
            "Started fetching 2 sources.", vm.fetchFromSourcesResult.value,
            "success is phrased as 'started fetching', not a completion count (endpoint is async post-#182)",
        )
        vm.close()
    }

    @Test
    fun fetchFromSourcesSingularMessageForOneFeed() = runTest {
        val repo = FakeFeedRepository(refreshUpstreamBehavior = { RefreshResult.Success(1) })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertEquals("Started fetching 1 source.", vm.fetchFromSourcesResult.value)
        vm.close()
    }

    @Test
    fun rateLimitedUpstreamFallsBackSilently() = runTest {
        // Upstream returns 429 (typed RateLimited result, not an exception):
        // the action must still re-read silently and update sync time, with no
        // error state — but fetchFromSourcesResult gets a "try again" message.
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { RefreshResult.RateLimited(retryAfterSeconds = 30) },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshUpstreamCallCount, "upstream pull was still attempted")
        assertEquals(1, repo.refreshCallCount, "must fall back to a plain re-read on 429")
        assertNotNull(vm.lastSyncTime.value, "sync time must still update on the silent fallback")
        assertFalse(vm.syncFailed.value, "a 429 fallback must NOT mark the sync as failed")
        assertEquals(UiState.Idle, vm.uiState.value, "a 429 fallback must NOT surface an error")
        assertTrue(
            vm.fetchFromSourcesResult.value.orEmpty().contains("try again", ignoreCase = true),
            "429 must surface a 'try again shortly' message, got: ${vm.fetchFromSourcesResult.value}",
        )
        vm.close()
    }

    @Test
    fun upstreamThrowStillReReadsAndDoesNotFailWholeRefresh() = runTest {
        // A non-429 upstream failure (e.g. transient 5xx surfaced as an exception)
        // degrades to a plain re-read — the cached list is still useful, so the
        // fetch as a whole still succeeds.
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { throw RuntimeException("upstream boom") },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshCallCount, "must still re-read the list when the upstream pull throws")
        assertNotNull(vm.lastSyncTime.value, "sync time updates from the successful re-read")
        assertFalse(vm.syncFailed.value, "an upstream failure that still re-reads must not fail the fetch")
        vm.close()
    }

    @Test
    fun reReadFailureStillFailsRefresh() = runTest {
        // If the plain re-read itself (action A) fails, that IS a real sync failure.
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { RefreshResult.Success(0) },
            refreshBehavior = { throw RuntimeException("server down") },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.syncFailed.value, "a failing re-read must still mark the sync as failed")
        vm.close()
    }

    // ── #129: own progress state, independent of the reflexive gesture's ─────

    @Test
    fun fetchFromSourcesUsesOwnProgressStateNeverIsRefreshing() = runTest {
        val gate = CompletableDeferred<RefreshResult>()
        val repo = FakeFeedRepository(refreshUpstreamBehavior = { gate.await() })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.runCurrent()

        assertTrue(vm.isFetchingFromSources.value, "fetchFromSources() must flip its own progress flag")
        assertFalse(
            vm.isRefreshing.value,
            "fetchFromSources() must NOT drive the shared isRefreshing/'Syncing…' indicator",
        )

        gate.complete(RefreshResult.Success(1))
        testScheduler.advanceUntilIdle()
        assertFalse(vm.isFetchingFromSources.value, "isFetchingFromSources must clear once the fetch completes")
        vm.close()
    }

    @Test
    fun fetchFromSourcesShortCircuitsWhileInFlight() = runTest {
        val gate = CompletableDeferred<RefreshResult>()
        val repo = FakeFeedRepository(refreshUpstreamBehavior = { gate.await() })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.runCurrent()
        vm.fetchFromSources() // second call while the first is still in flight
        testScheduler.runCurrent()

        assertEquals(1, repo.refreshUpstreamCallCount, "a concurrent call must short-circuit, not launch a second fetch")

        gate.complete(RefreshResult.Success(1))
        testScheduler.advanceUntilIdle()
        vm.close()
    }
}
