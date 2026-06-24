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
import kotlin.test.assertNull

/**
 * BUG-22: Article count mismatch between subscriptions badge and article list.
 *
 * Root cause: when a feed was selected, [FeedViewModel.selectFeed] only updated
 * a local filter — the repository still held a global top-50 article list. If a
 * feed had more articles than appeared in that global page, the article list
 * showed fewer items than the subscriptions badge counted.
 *
 * Fix: [selectFeed] now calls [FeedRepository.refreshForFeed] to fetch that
 * feed's articles from the server, and [refresh] when deselected (back to all).
 */
class FeedViewModelSelectFeedTest {

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
    fun selectFeedTriggersRefreshForFeed() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(42)
        testScheduler.advanceUntilIdle()

        assertEquals(42, vm.selectedFeedId.value, "selectedFeedId must be updated")
        assertEquals(1, repo.refreshForFeedCallCount, "must call refreshForFeed once")
        assertEquals(42, repo.lastRefreshForFeedId, "must pass the correct feedId")
        assertEquals(0, repo.refreshCallCount, "must NOT call global refresh()")
        vm.close()
    }

    @Test
    fun deselectFeedTriggersGlobalRefresh() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // First select a feed
        vm.selectFeed(42)
        testScheduler.advanceUntilIdle()

        // Then deselect
        vm.selectFeed(null)
        testScheduler.advanceUntilIdle()

        assertNull(vm.selectedFeedId.value, "selectedFeedId must be null after deselect")
        assertEquals(1, repo.refreshCallCount, "deselecting must call global refresh()")
        vm.close()
    }

    @Test
    fun selectSameFeedDoesNotRefetchArticles() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(7)
        testScheduler.advanceUntilIdle()

        // Select the same feed again
        vm.selectFeed(7)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.refreshForFeedCallCount,
            "selecting the same feed twice must NOT re-fetch")
        vm.close()
    }

    @Test
    fun selectDifferentFeedRefetchesArticles() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(1)
        testScheduler.advanceUntilIdle()
        vm.selectFeed(2)
        testScheduler.advanceUntilIdle()

        assertEquals(2, repo.refreshForFeedCallCount,
            "switching feeds must trigger refreshForFeed for the new feedId")
        assertEquals(2, repo.lastRefreshForFeedId, "last feedId must be 2")
        vm.close()
    }

    @Test
    fun selectFeedErrorDoesNotCrash() = runTest {
        val repo = FakeFeedRepository(
            refreshBehavior = { throw RuntimeException("network error") },
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(5)
        testScheduler.advanceUntilIdle()

        // Should not crash — error is logged and swallowed
        assertEquals(5, vm.selectedFeedId.value, "selectedFeedId must still be updated")
        vm.close()
    }
}
