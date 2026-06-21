package eu.monniot.feed.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Step 6 (§5.2/§5.3): the on-demand "fetch now" upstream-pull methods on
 * [FeedApi] must
 *  - POST to `v1/feeds/refresh` / `v1/feeds/{id}/refresh`,
 *  - return [RefreshResult.Success] with the feed count on 200, and
 *  - map a 429 to [RefreshResult.RateLimited] (the silent-fallback signal),
 *    NOT throw — while any other non-2xx still throws.
 */
class FeedApiRefreshTest {

    private fun makeApi(engine: MockEngine): FeedApi {
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FeedApi(client)
    }

    @Test
    fun refreshAllFeeds_200_returnsSuccessWithCount() = runTest {
        var seenPath: String? = null
        var seenMethod: HttpMethod? = null
        val engine = MockEngine { req ->
            seenPath = req.url.encodedPath
            seenMethod = req.method
            respond(
                content = """{"feeds_fetched": 3}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        val result = api.refreshAllFeeds()

        assertEquals(HttpMethod.Post, seenMethod, "must POST to refresh")
        assertTrue(seenPath!!.endsWith("v1/feeds/refresh"), "path was $seenPath")
        assertTrue(result is RefreshResult.Success, "expected Success, got $result")
        assertEquals(3, (result as RefreshResult.Success).feedsFetched)
    }

    @Test
    fun refreshAllFeeds_429_mapsToRateLimited() = runTest {
        val engine = MockEngine {
            respond(
                content = "rate limited",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf("Retry-After", "42"),
            )
        }
        val api = makeApi(engine)

        val result = api.refreshAllFeeds()

        assertTrue(result is RefreshResult.RateLimited, "429 must map to RateLimited, got $result")
        assertEquals(42L, (result as RefreshResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun refreshAllFeeds_500_throws() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val api = makeApi(engine)
        // 5xx isn't ClientRequestException; any non-2xx still surfaces as a throw.
        assertFailsWith<Exception> { api.refreshAllFeeds() }
    }

    @Test
    fun refreshFeed_postsToPerFeedPath() = runTest {
        var seenPath: String? = null
        val engine = MockEngine { req ->
            seenPath = req.url.encodedPath
            respond(
                content = """{"feeds_fetched": 1}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        val result = api.refreshFeed(feedId = 7)

        assertTrue(seenPath!!.endsWith("v1/feeds/7/refresh"), "path was $seenPath")
        assertTrue(result is RefreshResult.Success)
        assertEquals(1, (result as RefreshResult.Success).feedsFetched)
    }

    @Test
    fun refreshFeed_429_mapsToRateLimited() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.TooManyRequests) }
        val api = makeApi(engine)
        val result = api.refreshFeed(feedId = 7)
        assertTrue(result is RefreshResult.RateLimited, "got $result")
        // No Retry-After header → null.
        assertEquals(null, (result as RefreshResult.RateLimited).retryAfterSeconds)
    }
}
