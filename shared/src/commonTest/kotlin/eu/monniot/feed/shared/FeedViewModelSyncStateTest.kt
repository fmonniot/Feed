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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun okRepo(): FeedRepository = FakeFeedRepository()

private fun failingRepo(): FeedRepository =
    FakeFeedRepository(refreshBehavior = { throw RuntimeException("network error") })

class FeedViewModelSyncStateTest {

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
        val mixedRepo = FakeFeedRepository(
            refreshBehavior = { if (shouldFail) throw RuntimeException("network error") },
        )
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
        val mixedRepo = FakeFeedRepository(
            refreshBehavior = { if (shouldFail) throw RuntimeException("server down") },
        )
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
        val mixedRepo = FakeFeedRepository(
            refreshBehavior = { if (shouldFail) throw RuntimeException("no network") },
        )
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
        val gatedFailingRepo = FakeFeedRepository(
            refreshBehavior = {
                gate.await()
                throw RuntimeException("network error")
            },
        )
        val vm = makeVm(gatedFailingRepo, CoroutineScope(coroutineContext + Job()))

        // First refresh: run its body until it suspends inside repository.refresh().
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.isRefreshing.value, "precondition: first refresh is in flight")
        assertEquals(1, gatedFailingRepo.refreshCallCount, "precondition: exactly one network call so far")

        // Second refresh while the first is in flight: must short-circuit, no new launch.
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(1, gatedFailingRepo.refreshCallCount, "second refresh() must short-circuit — only one network call total")

        // Release the gate so the first refresh completes (and fails).
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.consecutiveFailures.value, "only one failure should be counted (no race)")
        assertFalse(vm.isRefreshing.value, "isRefreshing must clear after the in-flight refresh completes")
        vm.close()
    }
}
