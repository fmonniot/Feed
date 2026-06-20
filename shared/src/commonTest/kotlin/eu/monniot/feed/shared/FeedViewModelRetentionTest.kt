package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the retention sync behaviour in FeedViewModel (#37).
 *
 * Covers:
 * - updateKeepArticles writes to the repository (server sync)
 * - loadRetention reconciles the local pref with the server value
 * - KeepArticles.toDays / fromDays round-trip
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelRetentionTest {

    private fun makeVm(
        testScope: TestScope,
        repo: FakeFeedRepository = FakeFeedRepository(),
    ): Triple<FeedViewModel, UserPrefs, FakeFeedRepository> {
        val mockEngine = MockEngine { _ -> respond(content = "", status = HttpStatusCode.OK) }
        val client = HttpClient(mockEngine)
        val settings = InMemorySettings()
        val userPrefs = UserPrefs(settings)
        val vm = FeedViewModel(
            repository = repo,
            authApi = eu.monniot.feed.shared.api.AuthApi(client),
            sessionManager = SessionManager(),
            clearCookies = {},
            serverUrlStore = ServerUrlStore(settings),
            userPrefs = userPrefs,
            coroutineScope = CoroutineScope(testScope.coroutineContext + Job()),
        )
        return Triple(vm, userPrefs, repo)
    }

    // -----------------------------------------------------------------------
    // KeepArticles.toDays / fromDays round-trip
    // -----------------------------------------------------------------------

    @Test
    fun keepArticlesToDaysRoundTrip() {
        assertEquals(30, KeepArticles.Days30.toDays())
        assertEquals(90, KeepArticles.Days90.toDays())
        assertEquals(365, KeepArticles.Year1.toDays())
        assertEquals(null, KeepArticles.Forever.toDays())

        assertEquals(KeepArticles.Days30, KeepArticles.fromDays(30))
        assertEquals(KeepArticles.Days90, KeepArticles.fromDays(90))
        assertEquals(KeepArticles.Year1, KeepArticles.fromDays(365))
        assertEquals(KeepArticles.Forever, KeepArticles.fromDays(null))
    }

    @Test
    fun keepArticlesFromDaysUnknownValueReturnsNull() {
        assertEquals(null, KeepArticles.fromDays(7))
        assertEquals(null, KeepArticles.fromDays(180))
    }

    // -----------------------------------------------------------------------
    // updateKeepArticles syncs to repository
    // -----------------------------------------------------------------------

    @Test
    fun updateKeepArticlesSyncsToRepository() = runTest {
        val repo = FakeFeedRepository()
        val (vm, _, _) = makeVm(this, repo)

        vm.updateKeepArticles(KeepArticles.Days30)
        advanceUntilIdle()

        assertEquals(30, repo.retentionDays)
        assertEquals(KeepArticles.Days30, vm.prefs.value.keepArticles)
        vm.close()
    }

    @Test
    fun updateKeepArticlesForeverSyncsNullToRepository() = runTest {
        val repo = FakeFeedRepository()
        val (vm, _, _) = makeVm(this, repo)

        vm.updateKeepArticles(KeepArticles.Forever)
        advanceUntilIdle()

        assertEquals(null, repo.retentionDays)
        assertEquals(KeepArticles.Forever, vm.prefs.value.keepArticles)
        vm.close()
    }

    // -----------------------------------------------------------------------
    // loadRetention reconciles server → local pref
    // -----------------------------------------------------------------------

    @Test
    fun loadRetentionUpdatesLocalPrefFromServer() = runTest {
        val repo = FakeFeedRepository()
        repo.retentionDays = 365  // Server says 1 year
        val (vm, userPrefs, _) = makeVm(this, repo)

        // Local default is Days90
        assertEquals(KeepArticles.Days90, vm.prefs.value.keepArticles)

        vm.loadRetention()
        advanceUntilIdle()

        assertEquals(KeepArticles.Year1, vm.prefs.value.keepArticles)
        assertEquals(KeepArticles.Year1, userPrefs.snapshot().keepArticles)
        vm.close()
    }

    @Test
    fun loadRetentionForeverUpdatesLocalPref() = runTest {
        val repo = FakeFeedRepository()
        repo.retentionDays = null  // Server says forever
        val (vm, userPrefs, _) = makeVm(this, repo)

        vm.loadRetention()
        advanceUntilIdle()

        assertEquals(KeepArticles.Forever, vm.prefs.value.keepArticles)
        assertEquals(KeepArticles.Forever, userPrefs.snapshot().keepArticles)
        vm.close()
    }

    @Test
    fun loadRetentionNoOpWhenAlreadySynced() = runTest {
        val repo = FakeFeedRepository()
        repo.retentionDays = 90  // Server matches the default
        val (vm, userPrefs, _) = makeVm(this, repo)

        vm.loadRetention()
        advanceUntilIdle()

        // Should remain Days90
        assertEquals(KeepArticles.Days90, vm.prefs.value.keepArticles)
        assertEquals(KeepArticles.Days90, userPrefs.snapshot().keepArticles)
        vm.close()
    }

    @Test
    fun loadRetentionUnknownServerValueKeepsLocalPref() = runTest {
        val repo = FakeFeedRepository()
        repo.retentionDays = 7  // Unknown value — no KeepArticles variant for 7 days
        val (vm, _, _) = makeVm(this, repo)

        vm.loadRetention()
        advanceUntilIdle()

        // Local pref should stay unchanged (Days90 default)
        assertEquals(KeepArticles.Days90, vm.prefs.value.keepArticles)
        vm.close()
    }
}
