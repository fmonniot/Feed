package eu.monniot.feed.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * BUG-3: Verifies that [FeedApi.getParseError] maps a 404 response to null even when
 * [expectSuccess] is true (which causes Ktor to throw [io.ktor.client.plugins.ClientRequestException]
 * before the response body can be inspected).
 *
 * Before the fix, the `response.status == NotFound` branch was dead code: Ktor had
 * already thrown, so the null return was never reached. A 404 propagated as an uncaught
 * exception to [eu.monniot.feed.shared.FeedViewModel.loadParseError], which only logged
 * it without clearing `_parseError`, leaving the previous feed's stale value on screen.
 */
class FeedApiParseErrorTest {

    private val parseErrorJson = """
        {
          "feed_id": 42,
          "raw_body": "<html>not rss</html>",
          "response_status": 200,
          "content_type": "text/html",
          "byte_size": 20,
          "fetched_at": 1000000,
          "parser_error": "mismatched tag",
          "error_line": 1,
          "error_col": 5,
          "consecutive_fail_count": 2
        }
    """.trimIndent()

    /** Build a FeedApi whose HttpClient has `expectSuccess = true` (mirrors production). */
    private fun makeApi(engine: MockEngine): FeedApi {
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FeedApi(client)
    }

    // ── 404 → null ───────────────────────────────────────────────────────────

    @Test
    fun getParseError_404_returnsNull() = runTest {
        // Simulate the server returning 404 (no parse error on record for this feed).
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val api = makeApi(engine)

        // Before the fix this would throw ClientRequestException (uncaught) instead of null.
        val result = api.getParseError(feedId = 99)
        assertNull(result, "getParseError must return null when the server responds with 404 (BUG-3)")
    }

    // ── 200 with body → ApiResponse ──────────────────────────────────────────

    @Test
    fun getParseError_200_returnsBody() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"data": $parseErrorJson}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        val result = api.getParseError(feedId = 42)
        assertNotNull(result, "getParseError must return the response body on 200")
        assertNotNull(result.data, "getParseError result.data must be non-null on 200")
    }
}
