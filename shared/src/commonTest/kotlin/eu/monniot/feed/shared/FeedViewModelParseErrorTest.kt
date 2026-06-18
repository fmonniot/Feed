package eu.monniot.feed.shared

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.test.FakeFeedRepository
import eu.monniot.feed.shared.test.InMemorySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals

/**
 * BUG-3: Verifies that [FeedViewModel.loadParseError] never leaves stale parse-error
 * state from a previous feed when the current feed has no error (or the fetch fails).
 *
 * Root cause: with `expectSuccess = true`, Ktor throws [io.ktor.client.plugins.ClientRequestException]
 * on a 404 before the old `response.status == NotFound` branch in `FeedApi.getParseError`
 * could run, so the null-return path was dead code. `loadParseError`'s catch block only
 * logged the exception without resetting `_parseError`, leaving the previous feed's value.
 *
 * Fix:
 * 1. `FeedApi.getParseError` now catches `ClientRequestException` and returns null on 404.
 * 2. `FeedViewModel.loadParseError` clears `_parseError` before the fetch so failures
 *    also leave the state null rather than stale.
 */
class FeedViewModelParseErrorTest {

    private val sampleError = FeedParseError(
        feed_id = 1,
        raw_body = "<not xml>",
        response_status = 200,
        content_type = "text/html",
        byte_size = 9L,
        fetched_at = 1_000_000L,
        parser_error = "mismatched tag",
        error_line = 1L,
        error_col = 1L,
        consecutive_fail_count = 3L,
    )

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

    // ── initial state ─────────────────────────────────────────────────────────

    @Test
    fun parseError_startsNull() = runTest {
        val vm = makeVm(FakeFeedRepository(), CoroutineScope(coroutineContext + Job()))
        assertNull(vm.parseError.value, "parseError must be null before loadParseError is called")
        vm.close()
    }

    // ── null feedId clears immediately ────────────────────────────────────────

    @Test
    fun loadParseError_nullFeedId_clearsState() = runTest {
        // Seed an error value so we can confirm it is cleared synchronously.
        val repoWithError = object : FakeFeedRepository() {
            override suspend fun getParseError(feedId: Int): FeedParseError? = sampleError
        }
        val vm = makeVm(repoWithError, CoroutineScope(coroutineContext + Job()))

        vm.loadParseError(1)
        testScheduler.advanceUntilIdle()
        assertEquals(sampleError, vm.parseError.value, "precondition: error loaded for feed 1")

        vm.loadParseError(null)
        // null path is synchronous — no need to advance the scheduler
        assertNull(vm.parseError.value, "loadParseError(null) must clear parseError immediately")
        vm.close()
    }

    // ── repository returns null (feed has no parse error) ─────────────────────

    @Test
    fun loadParseError_repositoryReturnsNull_parsErrorIsNull() = runTest {
        // BUG-3 core case: feed B has no parse error → repository returns null →
        // parseError must be null, not whatever feed A left behind.
        val repoWithError = object : FakeFeedRepository() {
            override suspend fun getParseError(feedId: Int): FeedParseError? =
                if (feedId == 1) sampleError else null
        }
        val vm = makeVm(repoWithError, CoroutineScope(coroutineContext + Job()))

        // Load feed 1 — sets an error
        vm.loadParseError(1)
        testScheduler.advanceUntilIdle()
        assertEquals(sampleError, vm.parseError.value, "precondition: error loaded for feed 1")

        // Load feed 2 — no error; must clear the stale value
        vm.loadParseError(2)
        testScheduler.advanceUntilIdle()
        assertNull(vm.parseError.value, "parseError must be null for a feed with no parse error (BUG-3)")
        vm.close()
    }

    // ── repository throws (e.g. network error) ────────────────────────────────

    @Test
    fun loadParseError_repositoryThrows_parseErrorClearedNotStale() = runTest {
        // BUG-3: if the repository throws, _parseError must be null (cleared before
        // the fetch), not the previous feed's value.
        val throwingParseErrorRepo = object : FakeFeedRepository() {
            override suspend fun getParseError(feedId: Int): FeedParseError? =
                if (feedId == 1) sampleError else throw RuntimeException("network error")
        }
        val vm = makeVm(throwingParseErrorRepo, CoroutineScope(coroutineContext + Job()))

        // Seed feed 1's error
        vm.loadParseError(1)
        testScheduler.advanceUntilIdle()
        assertEquals(sampleError, vm.parseError.value, "precondition: error loaded for feed 1")

        // Now request feed 2 which throws — parseError must be null, not feed 1's stale value
        vm.loadParseError(2)
        testScheduler.advanceUntilIdle()
        assertNull(vm.parseError.value, "parseError must be null after fetch throws, not stale from previous feed (BUG-3)")
        vm.close()
    }

    // ── repository returns an error ───────────────────────────────────────────

    @Test
    fun loadParseError_repositoryReturnsError_parseErrorPopulated() = runTest {
        val repoWithError = object : FakeFeedRepository() {
            override suspend fun getParseError(feedId: Int): FeedParseError? = sampleError
        }
        val vm = makeVm(repoWithError, CoroutineScope(coroutineContext + Job()))

        vm.loadParseError(1)
        testScheduler.advanceUntilIdle()

        assertEquals(sampleError, vm.parseError.value, "parseError must be populated when repository returns a value")
        vm.close()
    }
}
