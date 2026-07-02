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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Unread view (no feed selected, `showAll = false`) queries the store with
 * [ArticleFilter.UnreadOnly] instead of windowing all articles and filtering
 * client-side. This fixes the inbox-zero-with-nonzero-badge bug: when the
 * newest page of articles is entirely read, the unread window must still
 * surface older unread articles.
 *
 * The just-opened article rides along in [ArticleFilter.UnreadOnly.keepArticleId]
 * so marking it read doesn't drop it from the list mid-read.
 */
class FeedViewModelUnreadViewTest {

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
    fun unreadViewUsesUnreadOnlyFilter() = runTest {
        val articles = listOf(
            makeArticle(id = "1"),
            makeArticle(id = "2").copy(isRead = true),
            makeArticle(id = "3"),
        )
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(null)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        assertEquals(listOf("1", "3"), items.map { it.id }, "read articles must not be in the unread view")
        assertIs<ArticleFilter.UnreadOnly>(repo.lastObservePageFilter, "store must be queried with UnreadOnly")
        vm.close()
    }

    /**
     * Regression for the original bug: badge says N unread, list shows inbox
     * zero. With the old `All` filter the first window held the 50 newest
     * articles — all read — and the client-side unread filter emptied it while
     * older unread articles existed past the window.
     */
    @Test
    fun unreadViewSurfacesUnreadArticlesBeyondNewestWindow() = runTest {
        // Newest DEFAULT_PAGE_SIZE articles are all read; unread ones come after.
        // FakeFeedRepository windows AFTER filtering, like the real stores.
        val newestRead = (1..FeedViewModel.DEFAULT_PAGE_SIZE).map {
            makeArticle(id = "read-$it").copy(isRead = true)
        }
        val olderUnread = (1..3).map { makeArticle(id = "unread-$it") }
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(newestRead + olderUnread))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(null)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        assertEquals(
            listOf("unread-1", "unread-2", "unread-3"),
            items.map { it.id },
            "unread articles past the newest window must be visible, not inbox zero",
        )
        vm.close()
    }

    @Test
    fun justReadArticleStaysVisibleUntilAnotherIsSelected() = runTest {
        val itemsFlow = MutableStateFlow(
            listOf(makeArticle(id = "1"), makeArticle(id = "2"), makeArticle(id = "3")),
        )
        val repo = FakeFeedRepository(itemsFlow = itemsFlow)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(null)
        vm.selectArticle("2")
        // Opening the article marks it read in the store.
        itemsFlow.update { items ->
            items.map { if (it.id == "2") it.copy(isRead = true) else it }
        }
        testScheduler.advanceUntilIdle()

        val whileSelected = vm.articleItems.filterNotNull().first()
        assertEquals(
            listOf("1", "2", "3"),
            whileSelected.map { it.id },
            "the just-read article must stay visible at its position while selected",
        )
        assertTrue(whileSelected.first { it.id == "2" }.isRead, "the kept article keeps its read state")

        // Moving on to another article drops the read one.
        vm.selectArticle("3")
        testScheduler.advanceUntilIdle()

        val afterMovingOn = vm.articleItems.filterNotNull().first()
        assertEquals(
            listOf("1", "3"),
            afterMovingOn.map { it.id },
            "the read article must drop out once another article is selected",
        )
        vm.close()
    }

    @Test
    fun deepLinkToReadArticleIsVisibleInUnreadView() = runTest {
        val articles = listOf(
            makeArticle(id = "1"),
            makeArticle(id = "2").copy(isRead = true),
        )
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Mirrors applyRouteToViewModel for #article/2 reached from the unread view.
        vm.selectFeed(null)
        vm.selectArticle("2")
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        assertEquals(
            listOf("1", "2"),
            items.map { it.id },
            "a reloaded deep link to a read article must keep it available to the reader pane",
        )
        vm.close()
    }

    @Test
    fun showAllViewIncludesReadArticles() = runTest {
        val articles = listOf(
            makeArticle(id = "1"),
            makeArticle(id = "2").copy(isRead = true),
        )
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(null, showAll = true)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        assertEquals(listOf("1", "2"), items.map { it.id }, "All articles view keeps read articles")
        assertIs<ArticleFilter.All>(repo.lastObservePageFilter)
        vm.close()
    }

    @Test
    fun reapplyingSameViewKeepsPagination() = runTest {
        val articles = (1..FeedViewModel.DEFAULT_PAGE_SIZE + 10).map { makeArticle(id = "$it") }
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // loadMore() reads hasMore, a WhileSubscribed StateFlow — keep both
        // flows collected for the whole scenario so they stay current.
        val articlesJob = launch { vm.articleItems.collect {} }
        val hasMoreJob = launch { vm.hasMore.collect {} }

        vm.selectFeed(null)
        testScheduler.advanceUntilIdle()
        assertEquals(FeedViewModel.DEFAULT_PAGE_SIZE, vm.articleItems.value?.size)

        assertTrue(vm.hasMore.value, "hasMore must be true with a full first page")
        vm.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(articles.size, vm.articleItems.value?.size, "loadMore must expand the window")

        // Re-applying the same route (e.g. opening an article) must not collapse
        // the expanded window back to one page.
        vm.selectFeed(null)
        vm.selectArticle("55")
        testScheduler.advanceUntilIdle()
        assertEquals(
            articles.size,
            vm.articleItems.value?.size,
            "pagination must survive re-selecting the current view",
        )
        articlesJob.cancel()
        hasMoreJob.cancel()
        vm.close()
    }
}
