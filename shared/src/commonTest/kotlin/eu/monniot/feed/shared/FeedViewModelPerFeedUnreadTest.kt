package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeArticle
import eu.monniot.feed.shared.test.makeFeed
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #115: pins the contract of [FeedViewModel.perFeedUnreadCounts] directly at the
 * shared layer, rather than only indirectly through the web sidebar DOM. Covers
 * the branches the web tests don't exercise: the empty-feeds branch, the
 * missing-articles case, and re-keying when the feed set changes.
 */
class FeedViewModelPerFeedUnreadTest {

    /** A repository whose feed list can be swapped between loadFeeds() calls. */
    private class MutableFeedsRepo(
        var feeds: List<Feed>,
        itemsFlow: MutableStateFlow<List<ArticleItem>>,
    ) : FakeFeedRepository(itemsFlow = itemsFlow) {
        override suspend fun getFeeds(): List<Feed> = feeds
    }

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

    /**
     * Subscribes to [FeedViewModel.perFeedUnreadCounts] (WhileSubscribed, so it
     * only recomputes while collected), lets the upstream settle, then snapshots
     * the current value.
     */
    private fun TestScope.awaitPerFeed(vm: FeedViewModel): Map<Int, Int> {
        val job = launch { vm.perFeedUnreadCounts.collect {} }
        testScheduler.advanceUntilIdle()
        val snapshot = vm.perFeedUnreadCounts.value
        job.cancel()
        return snapshot
    }

    @Test
    fun emptyFeeds_yields_emptyMap() = runTest {
        val repo = FakeFeedRepository(
            feedsToReturn = emptyList(),
            itemsFlow = MutableStateFlow(emptyList()),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        assertEquals(
            emptyMap(), awaitPerFeed(vm),
            "with no feeds the per-feed map must be empty (empty-feeds branch)",
        )
        vm.close()
    }

    @Test
    fun feedWithNoArticles_reports_zero() = runTest {
        // Two feeds, but only feed 1 has (unread) articles in the store.
        val articles = listOf(
            makeArticle(id = "1").copy(feedId = 1, isRead = false),
            makeArticle(id = "2").copy(feedId = 1, isRead = false),
        )
        val repo = FakeFeedRepository(
            feedsToReturn = listOf(
                makeFeed(id = 1, url = "https://example.com/1"),
                makeFeed(id = 2, url = "https://example.com/2"),
            ),
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        val counts = awaitPerFeed(vm)
        assertEquals(2, counts[1], "feed 1 has two unread articles")
        assertEquals(
            0, counts[2],
            "a feed present in _feeds but with no articles must report 0, not be absent",
        )
        vm.close()
    }

    @Test
    fun reKeys_whenFeedRemovedFromFeedSet() = runTest {
        val articles = listOf(
            makeArticle(id = "1").copy(feedId = 1, isRead = false),
            makeArticle(id = "2").copy(feedId = 2, isRead = false),
        )
        val repo = MutableFeedsRepo(
            feeds = listOf(
                makeFeed(id = 1, url = "https://example.com/1"),
                makeFeed(id = 2, url = "https://example.com/2"),
            ),
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()
        assertEquals(
            setOf(1, 2), awaitPerFeed(vm).keys,
            "both feeds must be keyed while both are in the feed set",
        )

        // Feed 2 is removed from the feed set; flatMapLatest must swap the
        // combined flow so feed 2's stale entry drops out of the map.
        repo.feeds = listOf(makeFeed(id = 1, url = "https://example.com/1"))
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        val counts = awaitPerFeed(vm)
        assertEquals(
            setOf(1), counts.keys,
            "removing a feed must drop its stale entry (re-keying on feed-set change)",
        )
        assertEquals(1, counts[1], "surviving feed's count is unchanged")
        vm.close()
    }
}
