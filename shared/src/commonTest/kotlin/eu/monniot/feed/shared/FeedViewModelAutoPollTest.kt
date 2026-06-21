package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.RefreshInterval
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Auto-poll (#38) tests for [FeedViewModel]. Every test drives the poll with the
 * test scheduler's VIRTUAL clock (`advanceTimeBy`) — never wall time — because the
 * poll loop runs on the VM's injected [CoroutineScope], which is wired to
 * `coroutineContext` here.
 *
 * The poll only runs while the client is in the foreground: it starts when a
 * platform calls [FeedViewModel.onForeground] (which also does an immediate re-read)
 * and stops on [FeedViewModel.onBackground]. The poll body is an unbounded
 * `while (isActive) { delay(); … }` loop, so `advanceUntilIdle()` must NOT be used
 * while it is active (it would drain future delays forever). These tests use
 * [runCurrent] and always [FeedViewModel.close] the VM (which cancels the loop)
 * before the test body ends so `runTest`'s end-of-test drain terminates.
 */
class FeedViewModelAutoPollTest {

    private val fifteenMin = 15 * 60 * 1000L

    private fun makeVm(
        repo: FakeFeedRepository,
        scope: CoroutineScope,
        settings: Settings = InMemorySettings(),
        interval: RefreshInterval = RefreshInterval.Manual,
        sessionManager: SessionManager = SessionManager(InMemorySettings()).apply { setLoggedIn(true) },
    ): FeedViewModel {
        // Seed the persisted interval BEFORE the VM is constructed so its poll starts
        // on the right cadence once the foreground signal arrives.
        UserPrefs(settings).setRefreshInterval(interval)
        return FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = sessionManager,
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
    }

    // (a) interval=15m → repository re-read ~once per 15 virtual minutes.
    @Test
    fun pollReReadsRepositoryEvery15MinutesAtMin15() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()), interval = RefreshInterval.Min15)
        // Foreground starts the poll and does one immediate re-read.
        vm.onForeground()
        runCurrent()
        assertEquals(1, repo.refreshCallCount, "foreground does one immediate re-read; the interval hasn't elapsed yet")

        testScheduler.advanceTimeBy(fifteenMin + 1)
        assertEquals(2, repo.refreshCallCount, "one more re-read after 15 virtual minutes")

        testScheduler.advanceTimeBy(fifteenMin)
        assertEquals(3, repo.refreshCallCount, "and another after a further 15 virtual minutes")

        testScheduler.advanceTimeBy(3 * fifteenMin)
        assertEquals(6, repo.refreshCallCount, "~one re-read per 15 virtual minutes")
        vm.close()
    }

    // (b) manual → NO automatic re-read (even while foregrounded).
    @Test
    fun manualIntervalPerformsNoAutomaticReRead() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()), interval = RefreshInterval.Manual)
        vm.onForeground()
        runCurrent()
        // Manual disables the cadence loop, so the only re-read is the immediate
        // foreground one — and no scheduled loop exists, so advanceUntilIdle is safe.
        assertEquals(1, repo.refreshCallCount, "foreground re-reads once; manual then schedules nothing")
        testScheduler.advanceTimeBy(6 * 60 * 60 * 1000L) // 6 virtual hours
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.refreshCallCount, "manual disables the auto-poll — no further re-reads ever")
        vm.close()
    }

    // (c) changing the pref changes the cadence live (no restart).
    @Test
    fun changingRefreshIntervalChangesCadenceLive() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()), interval = RefreshInterval.Manual)
        vm.onForeground()
        runCurrent()
        assertEquals(1, repo.refreshCallCount, "foreground re-read once; manual schedules no loop")
        testScheduler.advanceTimeBy(30 * 60 * 1000L)
        assertEquals(1, repo.refreshCallCount, "precondition: manual does not poll")

        // Switch to 15m live — the cadence starts without any restart.
        vm.updateRefreshInterval(RefreshInterval.Min15)
        testScheduler.advanceTimeBy(fifteenMin + 1)
        assertEquals(2, repo.refreshCallCount, "after switching to 15m, a re-read happens within 15 virtual minutes")

        // Switch back to manual — the cadence stops.
        vm.updateRefreshInterval(RefreshInterval.Manual)
        testScheduler.advanceTimeBy(60 * 60 * 1000L)
        assertEquals(2, repo.refreshCallCount, "switching back to manual stops the poll — count frozen")
        vm.close()
    }

    // (d) background pauses the poll; foreground triggers an immediate re-read + resumes.
    @Test
    fun backgroundPausesAndForegroundResumesWithImmediateReRead() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()), interval = RefreshInterval.Min15)
        vm.onForeground()
        runCurrent()
        assertEquals(1, repo.refreshCallCount, "initial foreground re-read")

        // Background → poll paused; no re-reads while hidden.
        vm.onBackground()
        testScheduler.advanceTimeBy(60 * 60 * 1000L) // an hour of virtual time
        assertEquals(1, repo.refreshCallCount, "no re-reads while backgrounded")

        // Foreground again → immediate re-read.
        vm.onForeground()
        runCurrent()
        assertEquals(2, repo.refreshCallCount, "foreground triggers an immediate re-read")

        // …and the interval resumes from there.
        testScheduler.advanceTimeBy(fifteenMin + 1)
        assertEquals(3, repo.refreshCallCount, "interval resumes after foreground")
        vm.close()
    }

    // (e) a poll re-read that throws is handled — no crash, routed through the error path,
    //     and a background poll failure is quiet (does not flip syncFailed).
    @Test
    fun pollReadThatThrowsIsHandledQuietly() = runTest {
        val repo = FakeFeedRepository(refreshBehavior = { throw RuntimeException("network error") })
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()), interval = RefreshInterval.Min15)
        // The immediate foreground re-read throws; it must be caught (no uncaught crash
        // that would fail runTest) and must not start the syncFailed path.
        vm.onForeground()
        runCurrent()
        assertEquals(1, repo.refreshCallCount, "the throwing foreground re-read still counts as an attempt")

        testScheduler.advanceTimeBy(fifteenMin + 1)
        assertEquals(2, repo.refreshCallCount, "the loop survives a throwing re-read and keeps polling")

        assertEquals(false, vm.syncFailed.value, "a background poll failure does not nag the user (ERR-1: quiet)")
        vm.close()
    }

    // Build a real 401 ClientRequestException for the poll-failure path.
    private suspend fun unauthorizedException(): ClientRequestException {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) { expectSuccess = false }
        return ClientRequestException(client.get("http://test/"), "")
    }

    // 401 from a poll re-read routes to the SESSION EXPIRED path (ERR-1).
    @Test
    fun pollReadUnauthorizedSurfacesSessionExpired() = runTest {
        // Build the 401 exception eagerly (it needs a suspend HTTP call) so the repo's
        // refresh behavior can throw it synchronously — keeping the poll path on the
        // virtual clock instead of depending on the MockEngine's own dispatch.
        val unauthorized = unauthorizedException()
        val repo = FakeFeedRepository(refreshBehavior = { throw unauthorized })
        val settings: Settings = InMemorySettings()
        UserPrefs(settings).setRefreshInterval(RefreshInterval.Min15)
        val sessionManager = SessionManager(InMemorySettings()).apply {
            setLoggedIn(true)
            setUsername("alice")
        }
        val vm = FeedViewModel(
            repository = repo,
            authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
            sessionManager = sessionManager,
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = CoroutineScope(coroutineContext + Job()),
        )
        vm.onForeground()
        runCurrent()
        assertEquals(
            "alice", vm.sessionExpiredUsername.value,
            "a 401 from the auto-poll must surface the SESSION EXPIRED modal",
        )
        vm.close()
    }
}
