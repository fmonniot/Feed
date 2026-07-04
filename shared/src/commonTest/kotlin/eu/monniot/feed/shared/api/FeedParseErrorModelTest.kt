package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * #24 contract tests: the client [FeedParseError] model must decode the shape
 * emitted by `GET /v1/feeds/{feed_id}/parse-error`
 * (server/src/db.rs FeedParseError struct, wrapped in ApiResponse by
 * get_feed_parse_error_handler in server/src/api/handlers.rs).
 */
class FeedParseErrorModelTest {

    @Test
    fun decodes_typical_parse_error_response() {
        val payload = """
            {
              "data": {
                "feed_id": 7,
                "raw_body": "<rss><channel><title>Broken",
                "response_status": 200,
                "content_type": "application/rss+xml",
                "byte_size": 28,
                "fetched_at": 1779031566,
                "parser_error": "unexpected end of input, expected closing tag",
                "error_line": 1,
                "error_col": 28,
                "consecutive_fail_count": 3
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<FeedParseError>>(payload)
        val error = response.data

        assertEquals(7, error.feed_id)
        assertEquals("<rss><channel><title>Broken", error.raw_body)
        assertEquals(200, error.response_status)
        assertEquals("application/rss+xml", error.content_type)
        assertEquals(28L, error.byte_size)
        assertEquals(1779031566L, error.fetched_at)
        assertEquals("unexpected end of input, expected closing tag", error.parser_error)
        assertEquals(1L, error.error_line)
        assertEquals(28L, error.error_col)
        assertEquals(3L, error.consecutive_fail_count)
    }

    @Test
    fun decodes_parse_error_with_null_optional_fields() {
        // raw_body, content_type, error_line, error_col can all be null (e.g. a
        // non-2xx HTTP response with no parseable body, or a parser error that
        // couldn't localize line/col).
        val payload = """
            {
              "data": {
                "feed_id": 9,
                "raw_body": null,
                "response_status": 503,
                "content_type": null,
                "byte_size": 0,
                "fetched_at": 1779031000,
                "parser_error": "HTTP 503 Service Unavailable",
                "error_line": null,
                "error_col": null,
                "consecutive_fail_count": 1
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<FeedParseError>>(payload)
        val error = response.data

        assertNull(error.raw_body)
        assertNull(error.content_type)
        assertNull(error.error_line)
        assertNull(error.error_col)
        assertEquals(503, error.response_status)
    }
}
