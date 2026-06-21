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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step 6 (§5.3): the primary refresh gesture must do action B (upstream pull)
 * *then* action A (plain re-read). When the upstream pull is rate-limited (429),
 * it must SILENTLY fall back to the plain re-read — no error state, sync time
 * still updates.
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
    fun refreshTriggersUpstreamPullThenReRead() = runTest {
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { RefreshResult.Success(2) },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshUpstreamCallCount, "primary refresh must trigger an upstream pull (action B)")
        assertEquals(1, repo.refreshCallCount, "primary refresh must re-read the list afterward (action A)")
        assertNotNull(vm.lastSyncTime.value, "sync time must update after a successful refresh")
        assertFalse(vm.syncFailed.value, "syncFailed must be false on success")
        vm.close()
    }

    @Test
    fun rateLimitedUpstreamFallsBackSilently() = runTest {
        // Upstream returns 429 (typed RateLimited result, not an exception):
        // the gesture must still re-read silently and update sync time, with no error.
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { RefreshResult.RateLimited(retryAfterSeconds = 30) },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshUpstreamCallCount, "upstream pull was still attempted")
        assertEquals(1, repo.refreshCallCount, "must fall back to a plain re-read on 429")
        assertNotNull(vm.lastSyncTime.value, "sync time must still update on the silent fallback")
        assertFalse(vm.syncFailed.value, "a 429 fallback must NOT mark the sync as failed")
        assertEquals(UiState.Idle, vm.uiState.value, "a 429 fallback must NOT surface an error")
        vm.close()
    }

    @Test
    fun upstreamThrowStillReReadsAndDoesNotFailWholeRefresh() = runTest {
        // A non-429 upstream failure (e.g. transient 5xx surfaced as an exception)
        // degrades to a plain re-read — the cached list is still useful, so the
        // refresh as a whole still succeeds.
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { throw RuntimeException("upstream boom") },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshCallCount, "must still re-read the list when the upstream pull throws")
        assertNotNull(vm.lastSyncTime.value, "sync time updates from the successful re-read")
        assertFalse(vm.syncFailed.value, "an upstream failure that still re-reads must not fail the refresh")
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

        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.syncFailed.value, "a failing re-read must still mark the sync as failed")
        vm.close()
    }
}
