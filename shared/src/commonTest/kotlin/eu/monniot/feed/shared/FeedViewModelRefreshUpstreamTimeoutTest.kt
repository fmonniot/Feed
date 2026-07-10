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

/**
 * #127 / #129: [FeedViewModel.fetchFromSources] (the explicit "Force fetch
 * from sources" Settings action — relocated here by #129 from the reflexive
 * refresh gesture, which no longer calls `refreshUpstream()` at all) must not
 * let its progress spinner hang on a slow `POST /v1/feeds/refresh` call. The
 * server now kicks the actual per-feed fetches off in the background and
 * responds promptly regardless of upstream latency
 * (server/src/api/handlers.rs), but the client also carries its own safety
 * net — [FeedViewModel.fetchFromSources] wraps `repository.refreshUpstream()`
 * in `withTimeoutOrNull(REFRESH_UPSTREAM_TIMEOUT)` — so the UI is robust even
 * if that HTTP call itself is slow for some unrelated reason.
 *
 * These tests simulate a hung upstream-pull call with a [CompletableDeferred]
 * that is never completed, standing in for "the server never responds to
 * POST /v1/feeds/refresh". `runTest`'s virtual time lets the timeout fire
 * deterministically without an actual multi-second wait.
 */
class FeedViewModelRefreshUpstreamTimeoutTest {

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
    fun isFetchingFromSourcesClearsWithoutWaitingForHungUpstreamPull() = runTest {
        // Never completed — stands in for a POST /v1/feeds/refresh that never
        // returns. A pre-#127 client would hang here forever (or until the
        // process itself gives up); the fix must clear the spinner regardless.
        val gate = CompletableDeferred<RefreshResult>()
        val repo = FakeFeedRepository(refreshUpstreamBehavior = { gate.await() })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        // Advances virtual time (including firing the internal
        // withTimeoutOrNull cancellation) without ever completing the gate.
        testScheduler.advanceUntilIdle()

        assertFalse(
            vm.isFetchingFromSources.value,
            "isFetchingFromSources must clear once REFRESH_UPSTREAM_TIMEOUT elapses, even though the upstream pull never completed",
        )
        // isFetchingFromSources is the progress flag for the whole action; isRefreshing
        // is only flipped briefly around the final re-read step (guarded against a
        // concurrent reflexive sync — see FeedViewModelFetchNowTest), so by the time
        // everything has settled it must be back to false here too.
        assertFalse(vm.isRefreshing.value, "isRefreshing must have settled back to false once fetchFromSources() completes")
        vm.close()
    }

    @Test
    fun fetchFromSourcesResultSurfacesMessageWhenUpstreamPullTimesOut() = runTest {
        // A timed-out upstream pull is silent to the re-read (action A still runs and
        // may succeed), but the explicit "Force fetch" action must not leave the row
        // indistinguishable from "never clicked" — say the fan-out never started.
        val gate = CompletableDeferred<RefreshResult>()
        val repo = FakeFeedRepository(refreshUpstreamBehavior = { gate.await() })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertEquals(
            "Could not reach the server — nothing was fetched.",
            vm.fetchFromSourcesResult.value,
            "a timed-out upstream pull must surface a message, not leave the row silently reverting to its default hint",
        )
        vm.close()
    }

    @Test
    fun plainReReadStillRunsAfterUpstreamPullTimesOut() = runTest {
        // §5.3 / #127: a timed-out upstream pull degrades exactly like any other
        // non-fatal failure — the cheap GET /v1/sync re-read (action A) must
        // still happen, which is how newly-fetched articles eventually surface
        // once the backgrounded server-side fetch (#127) completes.
        val gate = CompletableDeferred<RefreshResult>()
        val repo = FakeFeedRepository(refreshUpstreamBehavior = { gate.await() })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshCallCount, "the plain re-read must still run after the upstream pull times out")
        assertNotNull(vm.lastSyncTime.value, "lastSyncTime must still update from the plain re-read")
        vm.close()
    }

    @Test
    fun fastUpstreamPullIsUnaffectedByTheTimeout() = runTest {
        // Regression guard: a normal, fast upstream pull must not be delayed or
        // altered by the new timeout wrapper.
        val repo = FakeFeedRepository(refreshUpstreamBehavior = { RefreshResult.Success(3) })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.fetchFromSources()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshUpstreamCallCount, "the upstream pull must still run normally")
        assertEquals(1, repo.refreshCallCount, "the plain re-read must still follow a fast upstream pull")
        assertFalse(vm.isFetchingFromSources.value, "isFetchingFromSources must clear after a normal fast fetch")
        vm.close()
    }
}
