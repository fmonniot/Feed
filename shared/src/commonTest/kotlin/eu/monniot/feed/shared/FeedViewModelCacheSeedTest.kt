package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.sync.FeedMeta
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeFeed
import eu.monniot.feed.shared.test.makeFeedMeta
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-63 part 2: [FeedViewModel]'s init-time cache seed. [FeedViewModel.loadFeeds] /
 * [FeedViewModel.loadCategories] only ever populate [FeedViewModel.feeds] /
 * [FeedViewModel.categories] on a successful network call — before this fix, a cold start
 * with no connectivity left both permanently empty, so the sidebar/subscriptions screen
 * showed no feeds and no folders at all while offline. [FeedRepository.observeCachedFeeds] /
 * [FeedRepository.observeCachedCategories] let the persisted store (`FeedStore` /
 * `CategoryStore`) seed both lists once, before any network attempt — see
 * `SidebarOfflineTest` (web) for the DOM-level rendering of the same fix.
 */
class FeedViewModelCacheSeedTest {

    private fun makeVm(
        repo: FeedRepository,
        scope: CoroutineScope,
    ): FeedViewModel {
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

    // ── feeds seeded from cache, no network call ever made ────────────────────

    @Test
    fun feeds_seededFromCacheBeforeAnyLoadFeedsCall() = runTest {
        val cached = mapOf(
            1 to makeFeedMeta(
                id = 1, title = "Cached Feed", customTitle = null, categoryId = 5,
                errorCount = 3, serverFeedStatus = "error",
            ),
        )
        val repo = FakeFeedRepository(cachedFeedsToReturn = cached)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // loadFeeds() is deliberately never called — this is the offline cold-start case.
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.feeds.value.size, "the cache seed must populate feeds without any loadFeeds() call")
        val item = vm.feeds.value.first()
        assertEquals("Cached Feed", item.displayTitle)
        assertEquals(5, item.categoryId, "categoryId must round-trip so offline folder grouping works")
        assertEquals(3, item.errorCount)
        assertEquals("error", item.serverFeedStatus)
        vm.close()
    }

    @Test
    fun feeds_seededRowsAreFlaggedStale() = runTest {
        val repo = FakeFeedRepository(cachedFeedsToReturn = mapOf(1 to makeFeedMeta(id = 1)))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))
        testScheduler.advanceUntilIdle()

        assertTrue(
            vm.feeds.value.first().stale,
            "a row seeded from the cache (not a live loadFeeds() success) must be flagged stale " +
                "so the UI never presents its errorCount/serverFeedStatus/severity as current",
        )
        vm.close()
    }

    @Test
    fun feeds_emptyCacheLeavesFeedsEmpty() = runTest {
        // Default FakeFeedRepository has nothing cached — matches a fresh install.
        val vm = makeVm(FakeFeedRepository(), CoroutineScope(coroutineContext + Job()))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.feeds.value.isEmpty(), "no cache seed must fire when nothing is cached")
        vm.close()
    }

    // ── a throwing store degrades to "no seed", never to a crash ──────────────

    /**
     * The seed is best-effort, and the store implementations really do throw:
     * `IndexedDbFeedStore`/`IndexedDbCategoryStore` throw outright once another tab's
     * `versionchange` has force-closed this tab's connection, their cursor errors resume
     * exceptionally, and Room can surface `SQLiteException` on a locked/corrupt DB. An
     * exception escaping the seed `launch` would reach the thread's uncaught handler —
     * neither `viewModelScope` nor `CoroutineScope(SupervisorJob())` installs a
     * `CoroutineExceptionHandler` — and crash the Android app during ViewModel
     * construction. A cache miss must cost nothing more than an unseeded list.
     */
    @Test
    fun feeds_throwingCacheReadIsSwallowedAndLeavesFeedsEmpty() = runTest {
        val repo = object : FakeFeedRepository() {
            override fun observeCachedFeeds(): Flow<Map<Int, FeedMeta>> =
                flow { throw IllegalStateException("feed store closed by versionchange") }
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        testScheduler.advanceUntilIdle() // must not rethrow into the test's scope

        assertTrue(vm.feeds.value.isEmpty(), "a failed cache read must leave feeds unseeded, not propagate")
        vm.close()
    }

    @Test
    fun categories_throwingCacheReadIsSwallowedAndDoesNotBlockTheFeedSeed() = runTest {
        // Also pins that the two seeds are independent children: the categories read blowing
        // up must not take the feed seed down with it.
        val repo = object : FakeFeedRepository(cachedFeedsToReturn = mapOf(1 to makeFeedMeta(id = 1, title = "Cached Feed"))) {
            override fun observeCachedCategories(): Flow<List<Category>> =
                flow { throw IllegalStateException("category store closed by versionchange") }
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        testScheduler.advanceUntilIdle()

        assertTrue(vm.categories.value.isEmpty(), "a failed category cache read must leave categories unseeded")
        assertEquals(
            listOf("Cached Feed"), vm.feeds.value.map { it.displayTitle },
            "the feed seed must still land — the two cache reads are independent",
        )
        vm.close()
    }

    // ── categories seeded from cache, no network call ever made ───────────────

    @Test
    fun categories_seededFromCacheBeforeAnyLoadCategoriesCall() = runTest {
        val cached = listOf(Category(id = 1, name = "Tech", position = 0), Category(id = 2, name = "Craft", position = 1))
        val repo = FakeFeedRepository(cachedCategoriesToReturn = cached)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Tech", "Craft"), vm.categories.value.map { it.name })
        vm.close()
    }

    // ── a live load must never be clobbered by a slower cache read ────────────

    /**
     * The cache seed is a one-shot read at construction time, racing against whatever the
     * caller does next. If loadFeeds() wins that race (a fast, successful network call) the
     * live result must stick even once the slower cache read resolves afterward — otherwise
     * a real, current feed list would flicker back to a stale cached one.
     */
    @Test
    fun feeds_liveLoadWinningTheRaceIsNeverOverwrittenByASlowerCacheRead() = runTest {
        val live = listOf(makeFeed(id = 1, url = "https://example.com/live", title = "Live Feed"))
        val cached = mapOf(2 to makeFeedMeta(id = 2, title = "Cached Feed"))
        val repo = object : FakeFeedRepository(feedsToReturn = live) {
            override fun observeCachedFeeds(): Flow<Map<Int, FeedMeta>> = flow {
                delay(1000) // resolves strictly after the instant loadFeeds() call below
                emit(cached)
            }
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle() // drives both the load and the delayed cache read to completion

        assertEquals(listOf(1), vm.feeds.value.map { it.id }, "the live loadFeeds() result must win, not the slower cache read")
        assertFalse(vm.feeds.value.first().stale, "a live-loaded row must never be flagged stale")
        vm.close()
    }

    // ── session teardown must not be undone by a still-pending seed ───────────

    /**
     * `logout()` / `acknowledgeSessionExpired()` clear [FeedViewModel.feeds] synchronously,
     * but neither marks the feed list as live-loaded — so before the fix, a seed still
     * suspended on its store read resumed afterward, saw `haveLiveFeeds` still false, and
     * wrote the departing session's feeds back into the sidebar *on the login screen*.
     * `forgetDevice = true` doesn't help: `clearArticles()` empties the article mirror but
     * not the `FeedStore`, so the cache survives to be replayed.
     */
    @Test
    fun feeds_pendingSeedNeverRepopulatesAListClearedBySessionExpiry() = runTest {
        val cached = mapOf(1 to makeFeedMeta(id = 1, title = "Previous Session Feed"))
        val repo = object : FakeFeedRepository() {
            override fun observeCachedFeeds(): Flow<Map<Int, FeedMeta>> = flow {
                delay(1000) // still pending when the 401 lands
                emit(cached)
            }
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.acknowledgeSessionExpired(forgetDevice = true)
        testScheduler.advanceUntilIdle() // would resolve the cache read if it were still alive

        assertTrue(
            vm.feeds.value.isEmpty(),
            "a seed pending across session expiry must not resurrect the logged-out session's feeds",
        )
        vm.close()
    }

    @Test
    fun feeds_pendingSeedNeverRepopulatesAListClearedByLogout() = runTest {
        val cached = mapOf(1 to makeFeedMeta(id = 1, title = "Previous Session Feed"))
        val repo = object : FakeFeedRepository() {
            override fun observeCachedFeeds(): Flow<Map<Int, FeedMeta>> = flow {
                delay(1000)
                emit(cached)
            }
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.logout()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.feeds.value.isEmpty(), "a seed pending across logout must not resurrect the old feed list")
        vm.close()
    }

    @Test
    fun categories_pendingSeedNeverRepopulatesFoldersAfterLogout() = runTest {
        // The same job carries the category seed, so logout must take that down too —
        // otherwise the login screen's sidebar regrows the previous session's folders.
        val repo = object : FakeFeedRepository() {
            override fun observeCachedCategories(): Flow<List<Category>> = flow {
                delay(1000)
                emit(listOf(Category(id = 1, name = "Previous Session Folder", position = 0)))
            }
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.logout()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.categories.value.isEmpty(), "a seed pending across logout must not resurrect the old folders")
        vm.close()
    }

    @Test
    fun categories_liveLoadWinningTheRaceIsNeverOverwrittenByASlowerCacheRead() = runTest {
        val live = listOf(Category(id = 1, name = "Live", position = 0))
        val cached = listOf(Category(id = 2, name = "Cached", position = 0))
        val repo = object : FakeFeedRepository(categoriesToReturn = live) {
            override fun observeCachedCategories(): Flow<List<Category>> = flow {
                delay(1000)
                emit(cached)
            }
        }
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadCategories()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Live"), vm.categories.value.map { it.name })
        vm.close()
    }
}
