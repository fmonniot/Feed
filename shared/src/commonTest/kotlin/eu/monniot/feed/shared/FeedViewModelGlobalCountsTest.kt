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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BUG-43: the sidebar's "All articles"/"Unread" nav counters are global
 * navigation entries — they must always reflect all-feeds totals, not the
 * currently active filter or selected feed.
 *
 * [FeedViewModel.globalTotalCount] and [FeedViewModel.globalUnreadCount] are
 * the filter-independent flows that back those counters. This suite pins
 * that they stay stable across [FeedViewModel.selectFeed] calls that change
 * the view/filter — the exact operation that leaked the scoped
 * [FeedViewModel.articleItems]/[FeedViewModel.unreadCount] values into the
 * sidebar before the fix.
 */
class FeedViewModelGlobalCountsTest {

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
    fun globalTotalCountStaysStableAcrossFilterAndFeedSwitches() = runTest {
        // 3 articles across two feeds; 2 unread, 1 read.
        val articles = listOf(
            makeArticle(id = "1").copy(feedId = 1, isRead = false),
            makeArticle(id = "2").copy(feedId = 1, isRead = true),
            makeArticle(id = "3").copy(feedId = 2, isRead = false),
        )
        val repo = FakeFeedRepository(itemsFlow = MutableStateFlow(articles))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))
        val totalJob = launch { vm.globalTotalCount.collect {} }
        val unreadJob = launch { vm.globalUnreadCount.collect {} }
        testScheduler.advanceUntilIdle()

        // Baseline: Unread view (no feed selected, showAll = false).
        vm.selectFeed(null, showAll = false)
        testScheduler.advanceUntilIdle()
        assertEquals(3, vm.globalTotalCount.value, "total must count all articles regardless of view")
        assertEquals(2, vm.globalUnreadCount.value, "unread must count all unread articles regardless of view")

        // Switch to All articles view — the bug made totalCount track the
        // scoped articleItems.size, which happened to equal 3 here too, so
        // also check unread doesn't change and re-check after a feed switch
        // below, where the pre-fix behavior actually diverges.
        vm.selectFeed(null, showAll = true)
        testScheduler.advanceUntilIdle()
        assertEquals(3, vm.globalTotalCount.value, "total must not change when switching to All articles")
        assertEquals(2, vm.globalUnreadCount.value, "unread must not change when switching to All articles")

        // Select a single feed — pre-fix, unreadCount/articleItems.size would
        // scope down to that feed's own counts (BUG-43 symptom #2).
        vm.selectFeed(1)
        testScheduler.advanceUntilIdle()
        assertEquals(3, vm.globalTotalCount.value, "total must stay global when a feed is selected")
        assertEquals(2, vm.globalUnreadCount.value, "unread must stay global when a feed is selected")

        vm.selectFeed(2)
        testScheduler.advanceUntilIdle()
        assertEquals(3, vm.globalTotalCount.value, "total must stay global after switching feeds")
        assertEquals(2, vm.globalUnreadCount.value, "unread must stay global after switching feeds")

        totalJob.cancel()
        unreadJob.cancel()
        vm.close()
    }
}
