package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import eu.monniot.feed.shared.test.makeFeed
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Returns a real [ClientRequestException] with [status] via a MockEngine round-trip. */
private suspend fun buildException(status: HttpStatusCode): ClientRequestException {
    val engine = MockEngine { respond("", status) }
    val client = HttpClient(engine) { expectSuccess = false }
    val response = client.get("http://test/")
    return ClientRequestException(response, "")
}

/**
 * A [FakeFeedRepository] specialised for the add-feed validation suite:
 * - Returns [feedsToReturn] from [FeedRepository.getFeeds].
 * - Returns [categoriesToReturn] from [FeedRepository.getCategories].
 * - Throws [addFeedException] (if set) from [FeedRepository.addFeed], otherwise succeeds.
 * - [addFeedCallCount] (inherited) tracks how many times [FeedRepository.addFeed] was called.
 */
private fun testRepo(
    feedsToReturn: List<Feed> = emptyList(),
    categoriesToReturn: List<Category> = emptyList(),
    addFeedException: Exception? = null,
): FakeFeedRepository = FakeFeedRepository(
    feedsToReturn = feedsToReturn,
    categoriesToReturn = categoriesToReturn,
    addFeedBehavior = { if (addFeedException != null) throw addFeedException },
)

private fun makeVm(repo: FeedRepository, scope: CoroutineScope): FeedViewModel {
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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/**
 * ERR-12 / ERR-13: Validates that [FeedViewModel.addFeed] produces the correct
 * [AddFeedError] states without sending a server request when appropriate.
 */
class FeedViewModelAddFeedValidationTest {

    // -------------------------------------------------------------------------
    // ERR-13: duplicate URL → Duplicate error, no server request
    // -------------------------------------------------------------------------

    @Test
    fun duplicateUrl_setsDuplicateError_noServerCall() = runTest {
        val existingFeed = makeFeed(id = 1, url = "https://existing.example.com/feed")
        val repo = testRepo(feedsToReturn = listOf(existingFeed))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        // Pre-populate feeds by calling loadFeeds()
        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        // Now try to add the same URL
        vm.addFeed("https://existing.example.com/feed") {}
        testScheduler.advanceUntilIdle()

        val err = vm.addFeedError.value
        assertIs<AddFeedError.Duplicate>(err, "Expected Duplicate error, got $err")
        assertEquals(1, err.feedId)
        assertEquals("Feed 1", err.feedName)
        assertNull(err.folderName)

        // No POST /v1/feeds was sent
        assertEquals(0, repo.addFeedCallCount, "addFeed() must not be called for duplicate URL")
        vm.close()
    }

    @Test
    fun duplicateUrl_withFolder_includesFolderName() = runTest {
        val existingFeed = makeFeed(id = 5, url = "https://craft.example.com/rss", title = "Craft Blog", categoryId = 10)
        val category = Category(id = 10, name = "Craft", position = 0)
        val repo = testRepo(feedsToReturn = listOf(existingFeed), categoriesToReturn = listOf(category))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        vm.loadCategories()
        testScheduler.advanceUntilIdle()

        vm.addFeed("https://craft.example.com/rss") {}
        testScheduler.advanceUntilIdle()

        val err = vm.addFeedError.value
        assertIs<AddFeedError.Duplicate>(err)
        assertEquals("Craft Blog", err.feedName)
        assertEquals("Craft", err.folderName)
        vm.close()
    }

    @Test
    fun duplicateUrl_urlTrimmedBeforeCheck() = runTest {
        val existingFeed = makeFeed(id = 2, url = "https://trimmed.example.com/feed")
        val repo = testRepo(feedsToReturn = listOf(existingFeed))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        // URL with leading/trailing whitespace should still be detected as duplicate
        vm.addFeed("  https://trimmed.example.com/feed  ") {}
        testScheduler.advanceUntilIdle()

        assertIs<AddFeedError.Duplicate>(vm.addFeedError.value)
        assertEquals(0, repo.addFeedCallCount)
        vm.close()
    }

    // -------------------------------------------------------------------------
    // ERR-12: server 400 response → ParseFail error
    // -------------------------------------------------------------------------

    @Test
    fun serverReturns400_setsParseFail() = runTest {
        val badRequestException = buildException(HttpStatusCode.BadRequest)
        val repo = testRepo(addFeedException = badRequestException)
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        var successCalled = false
        vm.addFeed("https://not-a-feed.example.com") { successCalled = true }
        testScheduler.advanceUntilIdle()

        val err = vm.addFeedError.value
        assertIs<AddFeedError.ParseFail>(err, "Expected ParseFail for 400 response, got $err")
        assertEquals(false, successCalled, "onSuccess must not fire on 400 error")
        vm.close()
    }

    // -------------------------------------------------------------------------
    // Happy path: no duplicate, server succeeds → no error, onSuccess called
    // -------------------------------------------------------------------------

    @Test
    fun happyPath_noError_onSuccessCalled() = runTest {
        val repo = testRepo(feedsToReturn = emptyList())
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        var successCalled = false
        vm.addFeed("https://new.example.com/feed") { successCalled = true }
        testScheduler.advanceUntilIdle()

        assertNull(vm.addFeedError.value, "No error expected on happy path")
        assertEquals(true, successCalled, "onSuccess must be called on happy path")
        assertEquals(1, repo.addFeedCallCount)
        vm.close()
    }

    @Test
    fun differentUrl_noFalsePositive() = runTest {
        val existingFeed = makeFeed(id = 1, url = "https://existing.example.com/feed")
        val repo = testRepo(feedsToReturn = listOf(existingFeed))
        val vm = makeVm(repo, CoroutineScope(coroutineContext + Job()))

        vm.loadFeeds()
        testScheduler.advanceUntilIdle()

        var successCalled = false
        vm.addFeed("https://different.example.com/feed") { successCalled = true }
        testScheduler.advanceUntilIdle()

        assertNull(vm.addFeedError.value, "No duplicate error for a different URL")
        assertEquals(true, successCalled)
        assertEquals(1, repo.addFeedCallCount)
        vm.close()
    }
}
