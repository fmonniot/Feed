package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeArticle
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The article-list header's "N total" subtitle must reflect every article
 * matching the active filter, not just the [FeedViewModel.DEFAULT_PAGE_SIZE]
 * window loaded into [FeedViewModel.articleItems].
 *
 * Before this fix the header read `articleItems.value.size`, which is capped
 * at the window — with a feed of 200 articles and only one page loaded, the
 * header showed "N unread · 50 total" instead of the true total.
 */
class FeedViewModelTotalCountTest {

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

    private suspend fun TestScope.awaitTotalCount(vm: FeedViewModel): Int {
        val job = launch { vm.totalCount.collect {} }
        testScheduler.advanceUntilIdle()
        val total = vm.totalCount.value
        job.cancel()
        return total
    }

    @Test
    fun totalCount_exceedsWindow_whenMoreThanPageSizeArticlesExist() = runTest {
        val totalArticles = 120
        val articles = (1..totalArticles).map { i -> makeArticle(id = "$i", title = "Article $i") }
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(null, showAll = true)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        val total = awaitTotalCount(vm)

        assertEquals(
            FeedViewModel.DEFAULT_PAGE_SIZE, items.size,
            "list must be capped at DEFAULT_PAGE_SIZE (${FeedViewModel.DEFAULT_PAGE_SIZE})"
        )
        assertEquals(
            totalArticles, total,
            "totalCount must reflect every matching article ($totalArticles), not the window size"
        )
        vm.close()
    }

    @Test
    fun totalCount_all_countsReadAndUnread() = runTest {
        val articles = (1..10).map { i ->
            makeArticle(id = "$i", title = "Article $i").copy(isRead = i <= 4)
        }
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(null, showAll = true)
        testScheduler.advanceUntilIdle()

        val total = awaitTotalCount(vm)
        assertEquals(10, total, "All-articles totalCount must count both read and unread")
        vm.close()
    }

    @Test
    fun totalCount_perFeed_exceedsWindow_whenFeedHasMoreThanPageSizeArticles() = runTest {
        val feedId = 7
        val totalInFeed = 120
        val articles = (1..totalInFeed).map { i ->
            makeArticle(id = "$i", title = "Feed7 Article $i").copy(feedId = feedId)
        } + listOf(makeArticle(id = "other", title = "Other feed").copy(feedId = 99))
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(feedId)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        val total = awaitTotalCount(vm)

        assertEquals(
            FeedViewModel.DEFAULT_PAGE_SIZE, items.size,
            "per-feed list must be capped at DEFAULT_PAGE_SIZE"
        )
        assertEquals(
            totalInFeed, total,
            "per-feed totalCount must reflect every article in that feed, not the window or other feeds"
        )
        vm.close()
    }

    @Test
    fun totalCount_unreadView_matchesUnreadCount() = runTest {
        val articles = (1..10).map { i ->
            makeArticle(id = "$i", title = "Article $i").copy(isRead = i <= 3)
        }
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(null, showAll = false)
        testScheduler.advanceUntilIdle()

        val total = awaitTotalCount(vm)
        assertEquals(7, total, "Unread view's totalCount must equal the unread count (10 - 3 read)")
        vm.close()
    }

    @Test
    fun totalCount_updatesWhenSwitchingFeeds() = runTest {
        val articles = listOf(
            makeArticle(id = "1", title = "Feed A 1").copy(feedId = 1),
            makeArticle(id = "2", title = "Feed A 2").copy(feedId = 1),
            makeArticle(id = "3", title = "Feed B 1").copy(feedId = 2),
        )
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(1)
        testScheduler.advanceUntilIdle()
        assertEquals(2, awaitTotalCount(vm), "feed 1 has 2 articles")

        vm.selectFeed(2)
        testScheduler.advanceUntilIdle()
        assertEquals(1, awaitTotalCount(vm), "feed 2 has 1 article")
        vm.close()
    }
}
