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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

/** Throws RateLimitException directly — no Ktor mock needed. */
private fun rateLimitedRepo(retryAfterSeconds: Long = 60L): FeedRepository =
    FakeFeedRepository(refreshBehavior = { throw RateLimitException(retryAfterSeconds) })

private fun okRepo(): FeedRepository = FakeFeedRepository()

// ── Tests ─────────────────────────────────────────────────────────────────────

class FeedViewModelRateLimitTest {

    @Test
    fun rateLimitedUntilNullInitially() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertNull(vm.rateLimitedUntil.value, "rateLimitedUntil must be null before any refresh")
        vm.close()
    }

    @Test
    fun rateLimitDurationNullInitially() = runTest {
        val vm = makeVm(okRepo(), CoroutineScope(coroutineContext + Job()))
        assertNull(vm.rateLimitDuration.value, "rateLimitDuration must be null before any refresh")
        vm.close()
    }

    // runCurrent() runs ready coroutines without advancing virtual time, so the
    // rate-limit delay coroutine suspends at delay() rather than completing and
    // clearing the state before we can assert on it.

    @Test
    fun rateLimitedUntilSetAfterRateLimitException() = runTest {
        val vm = makeVm(rateLimitedRepo(120L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertNotNull(vm.rateLimitedUntil.value, "rateLimitedUntil must be set after a rate-limit response")
        vm.close()
    }

    @Test
    fun rateLimitDurationFormattedAsMinutes() = runTest {
        val vm = makeVm(rateLimitedRepo(120L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertEquals("2m", vm.rateLimitDuration.value, "120 seconds should format as '2m'")
        vm.close()
    }

    @Test
    fun rateLimitDurationFormattedAsSeconds() = runTest {
        val vm = makeVm(rateLimitedRepo(30L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertEquals("30s", vm.rateLimitDuration.value, "30 seconds should format as '30s'")
        vm.close()
    }

    @Test
    fun rateLimitClearsAfterDelay() = runTest {
        val vm = makeVm(rateLimitedRepo(60L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertNotNull(vm.rateLimitedUntil.value, "precondition: rate limit is set")
        testScheduler.advanceTimeBy(61_000L) // past the 60-second delay
        testScheduler.runCurrent()           // run the delay completion block
        assertNull(vm.rateLimitedUntil.value, "rateLimitedUntil must clear after the Retry-After delay elapses")
        assertNull(vm.rateLimitDuration.value, "rateLimitDuration must clear after the delay elapses")
        vm.close()
    }

    @Test
    fun normalFailureDoesNotSetRateLimit() = runTest {
        val failRepo = FakeFeedRepository(
            refreshBehavior = { throw RuntimeException("network error") },
        )
        val vm = makeVm(failRepo, CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertNull(vm.rateLimitedUntil.value, "non-429 failure must not set rateLimitedUntil")
        assertNull(vm.rateLimitDuration.value, "non-429 failure must not set rateLimitDuration")
        vm.close()
    }

    @Test
    fun rateLimitClearsOnSuccessBeforeRetryAfterElapses() = runTest {
        var callCount = 0
        val switchingRepo = FakeFeedRepository(
            refreshBehavior = {
                if (callCount++ == 0) throw RateLimitException(3600L) // 1-hour retry-after
            },
        )
        val vm = makeVm(switchingRepo, CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertNotNull(vm.rateLimitedUntil.value, "precondition: rateLimitedUntil set after 429")
        assertNotNull(vm.rateLimitDuration.value, "precondition: rateLimitDuration set after 429")
        // Second refresh succeeds — rate limit must clear without advancing past the retry-after
        vm.refresh()
        testScheduler.runCurrent()
        assertNull(vm.rateLimitedUntil.value, "rateLimitedUntil must be null after successful refresh")
        assertNull(vm.rateLimitDuration.value, "rateLimitDuration must be null after successful refresh")
        vm.close()
    }

    @Test
    fun rateLimitDoesNotIncrementConsecutiveFailures() = runTest {
        val vm = makeVm(rateLimitedRepo(60L), CoroutineScope(coroutineContext + Job()))
        vm.refresh()
        testScheduler.runCurrent()
        assertEquals(0, vm.consecutiveFailures.value, "429 must not increment consecutiveFailures")
        vm.close()
    }
}
