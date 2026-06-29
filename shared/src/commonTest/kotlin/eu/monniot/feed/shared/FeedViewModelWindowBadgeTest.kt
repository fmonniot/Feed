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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-34: Verifies the window-vs-badge contract at the production window size.
 *
 * The UI observes a fixed window of [FeedViewModel.DEFAULT_PAGE_SIZE] rows via
 * [FeedRepository.observePage], but [FeedRepository.observeUnreadCount] counts
 * all matching unread articles globally. When more than DEFAULT_PAGE_SIZE unread
 * articles exist, the badge exceeds the visible list length.
 *
 * This test pins the intended contract:
 * - badge == global unread count (COUNT(*) WHERE is_read = 0)
 * - list.size == min(total matching articles, DEFAULT_PAGE_SIZE) (capped window)
 * - when all articles are unread, badge >= list.size
 * - when some are read, badge may be less than list.size
 */
class FeedViewModelWindowBadgeTest {

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
     * Subscribes to [FeedViewModel.unreadCount] and waits for the first real
     * (post-initial) emission. The stateIn with WhileSubscribed(5000) only starts
     * the upstream when a subscriber is present; `.value` does not subscribe.
     * We skip the initial value (0) to get the real computed count.
     */
    private suspend fun awaitBadge(vm: FeedViewModel): Int =
        vm.unreadCount.drop(1).first()

    // -- Badge >= list at the production window size ----------------------------

    /**
     * With 55 unread articles (> DEFAULT_PAGE_SIZE=50), the list must be capped
     * to 50 while the badge shows the true global count of 55.
     */
    @Test
    fun badge_exceeds_list_when_unread_exceeds_page_size() = runTest {
        val totalUnread = 55
        val articles = (1..totalUnread).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        val items = vm.articleItems.filterNotNull().first()
        val badge = awaitBadge(vm)

        assertEquals(
            FeedViewModel.DEFAULT_PAGE_SIZE, items.size,
            "list must be capped at DEFAULT_PAGE_SIZE (${FeedViewModel.DEFAULT_PAGE_SIZE})"
        )
        assertEquals(
            totalUnread, badge,
            "badge must reflect the global unread count ($totalUnread), not the window size"
        )
        assertTrue(
            badge >= items.size,
            "badge ($badge) must be >= list size (${items.size})"
        )
        vm.close()
    }

    /**
     * When the number of unread articles is below the page size, badge == list.size.
     */
    @Test
    fun badge_equals_list_when_unread_within_page_size() = runTest {
        val totalUnread = 30
        val articles = (1..totalUnread).map { i ->
            makeArticle(id = "$i", title = "Article $i")
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        val items = vm.articleItems.filterNotNull().first()
        val badge = awaitBadge(vm)

        assertEquals(totalUnread, items.size, "list should contain all articles when under page size")
        assertEquals(totalUnread, badge, "badge should equal list size when under page size")
        vm.close()
    }

    /**
     * After marking some articles as read, badge reflects the unread subset while
     * the list still shows all articles (both read and unread) up to the window.
     */
    @Test
    fun badge_counts_only_unread_while_list_shows_all_states() = runTest {
        val articles = (1..10).map { i ->
            makeArticle(id = "$i", title = "Article $i").copy(
                isRead = i <= 3 // first 3 are read
            )
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        val items = vm.articleItems.filterNotNull().first()
        val badge = awaitBadge(vm)

        assertEquals(10, items.size, "list shows all articles (both read and unread)")
        assertEquals(7, badge, "badge counts only unread articles")
        assertTrue(badge <= items.size, "badge (unread only) <= list (all states)")
        vm.close()
    }

    // -- Per-feed filter: observePageByFeed returns all states, badge counts unread --

    /**
     * When a feed is selected, the list shows both read and unread articles for
     * that feed, while the badge counts only unread articles for that feed.
     */
    @Test
    fun perFeed_list_includes_read_articles_badge_counts_only_unread() = runTest {
        val feedId = 42
        val articles = listOf(
            makeArticle(id = "1", title = "Read Article").copy(feedId = feedId, isRead = true),
            makeArticle(id = "2", title = "Unread Article 1").copy(feedId = feedId, isRead = false),
            makeArticle(id = "3", title = "Unread Article 2").copy(feedId = feedId, isRead = false),
            makeArticle(id = "4", title = "Other Feed Article").copy(feedId = 99, isRead = false),
        )
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(feedId)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        val badge = awaitBadge(vm)

        assertEquals(
            3, items.size,
            "per-feed list must include both read and unread articles for the selected feed"
        )
        assertEquals(
            2, badge,
            "per-feed badge must count only unread articles for the selected feed"
        )
        assertTrue(items.any { it.isRead }, "list must contain at least one read article")
        assertTrue(items.none { it.feedId != feedId }, "list must not contain articles from other feeds")
        vm.close()
    }

    /**
     * With > DEFAULT_PAGE_SIZE articles in a single feed, the per-feed list is
     * also capped while the badge reflects the true unread count.
     */
    @Test
    fun perFeed_badge_exceeds_list_when_unread_exceeds_page_size() = runTest {
        val feedId = 7
        val totalArticles = 55
        val articles = (1..totalArticles).map { i ->
            makeArticle(id = "$i", title = "Feed7 Article $i").copy(feedId = feedId)
        }
        val repo = FakeFeedRepository(
            itemsFlow = MutableStateFlow(articles),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.selectFeed(feedId)
        testScheduler.advanceUntilIdle()

        val items = vm.articleItems.filterNotNull().first()
        val badge = awaitBadge(vm)

        assertEquals(
            FeedViewModel.DEFAULT_PAGE_SIZE, items.size,
            "per-feed list must be capped at DEFAULT_PAGE_SIZE"
        )
        assertEquals(
            totalArticles, badge,
            "per-feed badge must reflect global unread count for the feed"
        )
        assertTrue(badge >= items.size, "badge must be >= list size")
        vm.close()
    }
}
