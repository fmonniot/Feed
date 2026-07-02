package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeArticle
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Feed selection is now a local filter (§4.5) — no per-feed network fetch.
 *
 * [selectFeed] sets an [ArticleFilter] that drives [observePage] and
 * [observeUnreadCount]; the article list reacts to the filter change
 * through the store, not through a server re-fetch.
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
    fun selectFeedSetsFilterToByFeed() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(42)

        assertEquals(42, vm.selectedFeedId.value, "selectedFeedId must be updated")
        assertEquals(0, repo.refreshCallCount, "must NOT call refresh() — selection is a local filter")
        vm.close()
    }

    @Test
    fun deselectFeedSetsFilterToUnread() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(42)
        vm.selectFeed(null)
        testScheduler.advanceUntilIdle()
        vm.articleItems.filterNotNull().first()

        assertNull(vm.selectedFeedId.value, "selectedFeedId must be null after deselect")
        assertIs<ArticleFilter.UnreadOnly>(
            repo.lastObservePageFilter,
            "deselecting a feed returns to the unread view",
        )
        assertEquals(0, repo.refreshCallCount, "must NOT call refresh() — deselection is a local filter change")
        vm.close()
    }

    @Test
    fun selectFeedFiltersArticleItems() = runTest {
        val articles = listOf(
            makeArticle(id = "1", title = "Feed1 Article").copy(feedId = 1),
            makeArticle(id = "2", title = "Feed2 Article").copy(feedId = 2),
            makeArticle(id = "3", title = "Feed1 Other").copy(feedId = 1),
        )
        val repo = FakeFeedRepository(itemsFlow = kotlinx.coroutines.flow.MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(1)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        assertEquals(2, items.size, "only feed 1 articles should be visible")
        assertEquals("1", items[0].id)
        assertEquals("3", items[1].id)
        vm.close()
    }

    @Test
    fun selectSameFeedIsIdempotent() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(7)
        vm.selectFeed(7)

        assertEquals(7, vm.selectedFeedId.value)
        vm.close()
    }

    @Test
    fun switchingFeedsChangesFilter() = runTest {
        val articles = listOf(
            makeArticle(id = "1", title = "Feed1 Article").copy(feedId = 1),
            makeArticle(id = "2", title = "Feed2 Article").copy(feedId = 2),
        )
        val repo = FakeFeedRepository(itemsFlow = kotlinx.coroutines.flow.MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(1)
        testScheduler.advanceUntilIdle()
        val items1 = vm.articleItems.filterNotNull().first()
        assertEquals(1, items1.size, "only feed 1 article should be visible")

        vm.selectFeed(2)
        testScheduler.advanceUntilIdle()
        val items2 = vm.articleItems.filterNotNull().first()
        assertEquals(1, items2.size, "only feed 2 article should be visible")
        assertEquals("2", items2[0].id, "must show feed 2's article after switching")

        assertEquals(2, vm.selectedFeedId.value)
        vm.close()
    }
}
