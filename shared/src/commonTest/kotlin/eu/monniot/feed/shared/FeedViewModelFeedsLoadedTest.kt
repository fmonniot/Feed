package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-13: Verifies that [FeedViewModel.feedsLoaded] distinguishes
 * "not yet fetched" from "fetched and empty", so the first-run pane
 * is not shown before [FeedViewModel.loadFeeds] completes.
 */
class FeedViewModelFeedsLoadedTest {

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

    // ── feedsLoaded initial state ─────────────────────────────────────────────

    /**
     * Before [FeedViewModel.loadFeeds] is called the flag must be false.
     * A UI that shows the first-run pane on false would flash on every mount.
     */
    @Test
    fun feedsLoaded_startsFalse() = runTest {
        val vm = makeVm(FakeFeedRepository(), CoroutineScope(coroutineContext + Job()))
        assertFalse(vm.feedsLoaded.value, "feedsLoaded must be false before loadFeeds() is called")
        vm.close()
    }

    // ── feedsLoaded after successful load ─────────────────────────────────────

    /**
     * After [FeedViewModel.loadFeeds] completes with an empty list the flag
     * becomes true. The UI should now show the first-run pane.
     */
    @Test
    fun feedsLoaded_trueAfterLoadReturnsEmpty() = runTest {
        val repo = FakeFeedRepository(feedsToReturn = emptyList())
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.feedsLoaded.value, "feedsLoaded must be true after loadFeeds() returns an empty list")
        assertTrue(vm.feeds.value.isEmpty(), "feeds must be empty")
        vm.close()
    }

    /**
     * After [FeedViewModel.loadFeeds] completes with a non-empty list the flag
     * is true and the feed list is populated. The first-run pane should NOT show
     * (feedsLoaded && feeds.isEmpty() == false).
     */
    @Test
    fun feedsLoaded_trueAfterLoadReturnsFeeds() = runTest {
        val repo = FakeFeedRepository(feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/feed")))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.feedsLoaded.value, "feedsLoaded must be true after loadFeeds() returns feeds")
        assertFalse(vm.feeds.value.isEmpty(), "feeds must not be empty")
        vm.close()
    }

    // ── feedsLoaded after failed load ─────────────────────────────────────────

    /**
     * Even when [FeedViewModel.loadFeeds] throws (network error), [feedsLoaded]
     * must become true in the finally block. The UI should then show an error
     * rather than a perpetual blank state.
     */
    @Test
    fun feedsLoaded_trueAfterLoadFails() = runTest {
        // Subclass to make getFeeds() throw.
        val failingRepo = object : FakeFeedRepository() {
            override suspend fun getFeeds(): List<eu.monniot.feed.shared.api.Feed> =
                throw RuntimeException("network error")
        }
        val vm = makeVm(failingRepo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.feedsLoaded.value, "feedsLoaded must be true even when loadFeeds() fails, to avoid perpetual blank state")
        vm.close()
    }

    // ── logout clears feed state ──────────────────────────────────────────────

    /**
     * After [FeedViewModel.logout], both [FeedViewModel.feeds] and
     * [FeedViewModel.feedsLoaded] must be reset so a subsequent user (or a
     * re-login to a different server) never sees stale data.
     */
    @Test
    fun logout_clearsFeedsAndFeedsLoaded() = runTest {
        val repo = FakeFeedRepository(feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/feed")))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.feedsLoaded.value, "precondition: feedsLoaded must be true after load")
        assertFalse(vm.feeds.value.isEmpty(), "precondition: feeds must be populated")

        vm.logout()
        testScheduler.advanceUntilIdle()

        assertFalse(vm.feedsLoaded.value, "feedsLoaded must be false after logout")
        assertTrue(vm.feeds.value.isEmpty(), "feeds must be empty after logout")
        vm.close()
    }
}
