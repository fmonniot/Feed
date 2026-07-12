package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #122: shared-layer category management (create / rename / delete-with-
 * reassign / reorder / move-feed) and the per-feed action set (rename /
 * interval / pause-resume / unsubscribe — refresh-now is covered by
 * [FeedViewModelRefreshFeedTest]) exposed uniformly through [FeedViewModel]
 * for both clients.
 *
 * Each mutation must (a) delegate to the right [FeedRepository] method with
 * the right arguments, and (b) refresh the affected `StateFlow`(s) —
 * [FeedViewModel.categories] and/or [FeedViewModel.feeds] — without requiring
 * the caller to trigger a full reload.
 */
class FeedViewModelCategoryManagementTest {

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

    // ── Category CRUD / move / reorder ──────────────────────────────────────

    @Test
    fun createCategory_delegatesAndRefreshesCategories() = runTest {
        val repo = FakeFeedRepository(
            categoriesToReturn = listOf(Category(id = 100, name = "Tech", position = 0)),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.createCategory("Tech")
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.createCategoryCallCount)
        assertEquals("Tech", repo.lastCreateCategoryName)
        assertEquals(listOf(Category(id = 100, name = "Tech", position = 0)), vm.categories.value,
            "categories StateFlow must reflect the server's authoritative list after create")
        vm.close()
    }

    @Test
    fun createCategory_invokesOnSuccessWithServerAssignedId() = runTest {
        // SUBS-10: the Move sheet's "+ New category…" chains a feed move onto the
        // new category's server-assigned id — so onSuccess must fire (after the
        // categories refresh) carrying that id, not a client-side guess.
        val repo = FakeFeedRepository(
            categoriesToReturn = listOf(Category(id = 42, name = "Longreads", position = 0)),
        )
        repo.createCategoryIdToReturn = 42
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        var receivedId: Int? = null
        vm.createCategory("Longreads") { id -> receivedId = id }
        testScheduler.advanceUntilIdle()

        assertEquals(42, receivedId, "onSuccess must receive the server-assigned category id")
        assertEquals(listOf(Category(id = 42, name = "Longreads", position = 0)), vm.categories.value,
            "categories must already reflect the new category when onSuccess fires")
        vm.close()
    }

    @Test
    fun renameCategory_delegatesAndRefreshesCategories() = runTest {
        val repo = FakeFeedRepository(
            categoriesToReturn = listOf(Category(id = 3, name = "Renamed", position = 0)),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.renameCategory(3, "Renamed")
        testScheduler.advanceUntilIdle()

        assertEquals(3, repo.lastRenameCategoryId)
        assertEquals("Renamed", repo.lastRenameCategoryName)
        assertEquals("Renamed", vm.categories.value.single().name,
            "categories StateFlow must reflect the rename")
        vm.close()
    }

    @Test
    fun deleteCategory_withReassign_passesTargetAndRefreshesCategoriesAndFeeds() = runTest {
        val repo = FakeFeedRepository(
            categoriesToReturn = emptyList(), // category 3 is gone after delete
            feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/1", categoryId = 42)),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.deleteCategory(categoryId = 3, reassignTo = 42)
        testScheduler.advanceUntilIdle()

        assertEquals(3, repo.lastDeleteCategoryId)
        assertEquals(42, repo.lastDeleteCategoryReassignTo)
        assertTrue(vm.categories.value.isEmpty(), "categories StateFlow must reflect the deletion")
        assertEquals(1, vm.feeds.value.size, "feeds StateFlow must be refreshed since category_id assignments may have changed")
        assertEquals(42, vm.feeds.value.single().categoryId, "the reassigned feed's category must show the new target")
        vm.close()
    }

    @Test
    fun deleteCategory_withoutReassign_passesNullReassignTarget() = runTest {
        val repo = FakeFeedRepository()
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.deleteCategory(categoryId = 3, reassignTo = null)
        testScheduler.advanceUntilIdle()

        assertEquals(3, repo.lastDeleteCategoryId)
        assertNull(repo.lastDeleteCategoryReassignTo,
            "null reassignTo must reach the repository so it lets ON DELETE SET NULL apply")
        assertTrue(repo.deleteCategoryReassignToWasSet)
        vm.close()
    }

    @Test
    fun reorderCategories_delegatesWithOrderedIdsAndRefreshesCategories() = runTest {
        val repo = FakeFeedRepository(
            categoriesToReturn = listOf(
                Category(id = 9, name = "C", position = 0),
                Category(id = 2, name = "A", position = 1),
                Category(id = 5, name = "B", position = 2),
            ),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.reorderCategories(listOf(9, 2, 5))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(9, 2, 5), repo.lastReorderCategoryIds)
        assertEquals(3, vm.categories.value.size, "categories StateFlow must be refreshed after reorder")
        vm.close()
    }

    @Test
    fun setFeedCategory_movesAndRefreshesFeeds() = runTest {
        val repo = FakeFeedRepository(
            feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/1", categoryId = 7)),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.setFeedCategory(feedId = 1, categoryId = 7)
        testScheduler.advanceUntilIdle()

        assertTrue(repo.setFeedCategoryWasCalled)
        assertEquals(1, repo.lastSetFeedCategoryId)
        assertEquals(7, repo.lastSetFeedCategoryCategoryId)
        assertEquals(7, vm.feeds.value.single().categoryId,
            "feeds StateFlow must reflect the new category without a full reload")
        vm.close()
    }

    // ── Per-feed action set (SUBS-1/#3 reuse: rename / interval / pause / unsubscribe) ──
    // Refresh-now is already covered by FeedViewModelRefreshFeedTest.

    @Test
    fun renameFeed_updatesCustomTitleAndRefreshesFeeds() = runTest {
        val repo = FakeFeedRepository(
            feedsToReturn = listOf(
                makeFeed(id = 1, url = "https://example.com/1").copy(custom_title = "New name"),
            ),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        vm.renameFeed(1, "New name")
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.updateFeedCallCount)
        assertEquals("New name", repo.lastUpdateFeedCall?.customTitle)
        assertEquals("New name", vm.feeds.value.single().displayTitle)
        vm.close()
    }

    @Test
    fun setFeedInterval_updatesIntervalAndRefreshesFeeds() = runTest {
        val repo = FakeFeedRepository(
            feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/1")),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        vm.setFeedInterval(1, 15)
        testScheduler.advanceUntilIdle()

        assertEquals(15, repo.lastUpdateFeedCall?.fetchIntervalMinutes,
            "must pass the new interval (e.g. 15m / 1h / 6h / Daily) through to the repository")
        assertNull(vm.feedsError.value)
        vm.close()
    }

    @Test
    fun toggleFeedPaused_pausesAndResumesAndRefreshesFeeds() = runTest {
        val repo = FakeFeedRepository(
            feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/1")),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        vm.toggleFeedPaused(1, true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, repo.lastUpdateFeedCall?.isPaused, "pause must set is_paused=true")

        vm.toggleFeedPaused(1, false)
        testScheduler.advanceUntilIdle()
        assertEquals(false, repo.lastUpdateFeedCall?.isPaused, "resume must set is_paused=false")

        assertEquals(2, repo.updateFeedCallCount)
        vm.close()
    }

    @Test
    fun deleteFeed_unsubscribesAndRefreshesFeeds() = runTest {
        val repo = FakeFeedRepository(
            feedsToReturn = listOf(makeFeed(id = 1, url = "https://example.com/1")),
        )
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.feeds.value.size, "precondition: feed is loaded")

        vm.deleteFeed(1)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.lastDeleteFeedId)
        assertNull(vm.feedsError.value, "no error should be surfaced on success")
        vm.close()
    }
}
