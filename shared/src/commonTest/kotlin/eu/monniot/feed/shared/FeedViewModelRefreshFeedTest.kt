package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeFeed
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
import kotlin.test.assertTrue

/**
 * #78: per-feed refresh via [FeedViewModel.refreshFeed].
 *
 * Happy path: upstream pull succeeds, feed list is re-loaded.
 * Rate-limited path: 429 returns [RefreshResult.RateLimited]; the function
 * silently falls back to a re-read (no error surfaced), consistent with the
 * global [FeedViewModel.refresh] gesture.
 */
class FeedViewModelRefreshFeedTest {

    private fun makeVm(repo: FakeFeedRepository, scope: CoroutineScope): FeedViewModel {
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
    fun refreshFeedHappyPath() = runTest {
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { RefreshResult.Success(1) },
            feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/feed")),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.refreshFeed(1)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshUpstreamCallCount, "must call refreshFeedUpstream once")
        assertEquals(1, repo.lastRefreshFeedUpstreamId, "must pass the correct feedId")
        assertNull(vm.feedsError.value, "no error should be surfaced on success")
        assertTrue(repo.getFeedsCallCount >= 1, "loadFeeds() must re-read the feed list after refresh (getFeedsCallCount=${repo.getFeedsCallCount})")
        vm.close()
    }

    @Test
    fun refreshFeedRateLimitedFallsBackSilently() = runTest {
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { RefreshResult.RateLimited(retryAfterSeconds = 45) },
            feedsToReturn = listOf(makeFeed(id = 2, url = "https://example.com/feed2")),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.refreshFeed(2)
        // Use runCurrent() instead of advanceUntilIdle() so the refreshFeed
        // coroutine and handleRateLimit launch execute, but the 45-second
        // cooldown delay does NOT complete (which would clear the state).
        testScheduler.runCurrent()

        assertEquals(1, repo.refreshUpstreamCallCount, "upstream pull was attempted")
        assertEquals(2, repo.lastRefreshFeedUpstreamId, "must pass the correct feedId")
        assertNull(vm.feedsError.value, "a 429 must NOT surface an error (silent fallback)")
        assertNotNull(vm.rateLimitedUntil.value, "per-feed 429 must start the shared cooldown timer")
        assertEquals("45s", vm.rateLimitDuration.value, "duration label must reflect the Retry-After value")
        vm.close()
    }

    @Test
    fun refreshFeedOtherErrorSurfacesError() = runTest {
        val repo = FakeFeedRepository(
            refreshUpstreamBehavior = { throw RuntimeException("server error") },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.refreshFeed(3)
        testScheduler.advanceUntilIdle()

        assertEquals("Failed to refresh feed", vm.feedsError.value,
            "a non-429 failure must surface an error message")
        vm.close()
    }
}
