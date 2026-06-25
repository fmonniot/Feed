package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.LoginRequest
import eu.monniot.feed.shared.api.LoginResponse
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
import kotlin.test.assertTrue

/**
 * BUG-30: after a successful login, [FeedViewModel] must trigger an immediate
 * article refresh so the feed screen is not empty. Before the fix, `login()`
 * called `restartPoll()` (which delays before the first read) but never called
 * `refresh()`, leaving articles empty until the poll interval elapsed or the
 * user manually pulled to refresh.
 *
 * These tests use a subclassed [AuthApi] that bypasses the HTTP layer entirely,
 * avoiding MockEngine dispatching differences between JVM and JS targets.
 */
class FeedViewModelLoginRefreshTest {

    /**
     * A test-only [AuthApi] that succeeds without making any HTTP call.
     * This sidesteps MockEngine's async dispatching on JS, which uses the
     * browser event loop rather than the test scheduler.
     */
    private class FakeAuthApi : AuthApi(
        HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
    ) {
        override suspend fun login(request: LoginRequest): LoginResponse {
            return LoginResponse(username = request.username)
        }
    }

    private fun makeVm(
        repo: FakeFeedRepository,
        scope: CoroutineScope,
        sessionManager: SessionManager = SessionManager(InMemorySettings()),
    ): FeedViewModel {
        val settings: Settings = InMemorySettings()
        return FeedViewModel(
            repository = repo,
            authApi = FakeAuthApi(),
            sessionManager = sessionManager,
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = UserPrefs(settings),
            coroutineScope = scope,
        )
    }

    @Test
    fun loginTriggersImmediateRefresh() = runTest {
        val repo = FakeFeedRepository()
        val sessionManager = SessionManager(InMemorySettings())
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()), sessionManager)

        assertEquals(0, repo.refreshCallCount, "precondition: no refresh before login")
        assertEquals(0, repo.refreshUpstreamCallCount, "precondition: no upstream pull before login")

        vm.login("testuser", "password")
        testScheduler.advanceUntilIdle()

        // Verify the user is logged in (check the underlying session manager, not the
        // ViewModel's WhileSubscribed StateFlow which requires active collectors).
        assertTrue(sessionManager.isLoggedIn.value,
            "user must be logged in after successful login; loginError=${vm.loginError.value}")

        // After login, FeedViewModel.refresh() should have been called, which
        // triggers both an upstream pull (refreshUpstream) and a re-read (refresh).
        assertTrue(
            repo.refreshUpstreamCallCount >= 1,
            "login must trigger an upstream pull; got ${repo.refreshUpstreamCallCount}",
        )
        assertTrue(
            repo.refreshCallCount >= 1,
            "login must trigger a repository re-read; got ${repo.refreshCallCount}",
        )
        vm.close()
    }

    @Test
    fun loginRefreshSetsLastSyncTime() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        assertEquals(null, vm.lastSyncTime.value, "precondition: no sync time before login")

        vm.login("testuser", "password")
        testScheduler.advanceUntilIdle()

        // The successful refresh() sets lastSyncTime.
        assertTrue(
            vm.lastSyncTime.value != null,
            "lastSyncTime must be set after login triggers a refresh",
        )
        vm.close()
    }
}
