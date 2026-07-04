package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * #24 contract tests: the client [Feed] model must decode the full set of
 * health-diagnostic fields the server flattens onto `FeedWithUnread`
 * (server/src/db.rs) for `GET /v1/feeds` and `GET /v1/feeds/{id}`:
 * `feed_status`, `severity`, `last_error_kind`, `last_http_status`,
 * `consecutive_failure_count`, `retries_paused`, `next_retry_at`, plus the
 * #81-era `first_410_at`.
 *
 * [FeedModelTest] already covers the BUG-5 nullable-title regression; this
 * file extends coverage to the newer diagnostic fields so drift there is
 * caught too.
 */
class FeedHealthFieldsModelTest {

    @Test
    fun decodes_healthy_feed_with_all_diagnostic_fields_absent() {
        // "ok" feeds omit severity/consecutive_failure_count/retries_paused/next_retry_at/
        // last_error_kind/last_http_status entirely rather than sending them as null
        // (#[serde(skip_serializing_if = "Option::is_none")] on the server side), and
        // unread_count is never sent at all (#[serde(skip_serializing)] on FeedWithUnread).
        val feedJson = """
            {
              "id": 1,
              "url": "https://example.com/feed.xml",
              "title": "Example Feed",
              "custom_title": null,
              "is_paused": false,
              "fetch_interval_minutes": 30,
              "error_count": 0,
              "last_fetched": 1640995200,
              "category_id": null,
              "feed_status": "ok"
            }
        """.trimIndent()

        val feed = json.decodeFromString<Feed>(feedJson)
        assertEquals("ok", feed.feed_status)
        assertNull(feed.severity)
        assertNull(feed.last_error_kind)
        assertNull(feed.last_http_status)
        assertNull(feed.consecutive_failure_count)
        assertNull(feed.retries_paused)
        assertNull(feed.next_retry_at)
        assertNull(feed.first_410_at)
    }

    @Test
    fun decodes_dead_feed_with_all_diagnostic_fields_present() {
        // A feed with >= 14 consecutive 410s: dead, retries paused, severity "error".
        // next_retry_at is None for dead feeds (with_parse_fail_count in db.rs), so the
        // server omits the key rather than sending null.
        val feedJson = """
            {
              "id": 2,
              "url": "https://gone.example.com/feed.xml",
              "title": "Gone Feed",
              "custom_title": null,
              "is_paused": false,
              "fetch_interval_minutes": 30,
              "error_count": 14,
              "last_fetched": 1640995200,
              "category_id": null,
              "feed_status": "dead",
              "severity": "error",
              "last_error_kind": "http_410",
              "last_http_status": 410,
              "consecutive_failure_count": 14,
              "retries_paused": true,
              "first_410_at": 1640990000
            }
        """.trimIndent()

        val feed = json.decodeFromString<Feed>(feedJson)
        assertEquals("dead", feed.feed_status)
        assertEquals("error", feed.severity)
        assertEquals("http_410", feed.last_error_kind)
        assertEquals(410, feed.last_http_status)
        assertEquals(14, feed.consecutive_failure_count)
        assertEquals(true, feed.retries_paused)
        assertNull(feed.next_retry_at)
        assertEquals(1640990000L, feed.first_410_at)
    }

    @Test
    fun decodes_erroring_feed_pending_retry() {
        // A feed with a 5xx/network error, not yet dead: severity "warn", next_retry_at set.
        val feedJson = """
            {
              "id": 3,
              "url": "https://flaky.example.com/feed.xml",
              "title": "Flaky Feed",
              "custom_title": "My Flaky Feed",
              "is_paused": false,
              "fetch_interval_minutes": 60,
              "error_count": 3,
              "last_fetched": 1640995200,
              "category_id": 7,
              "feed_status": "error",
              "severity": "warn",
              "last_error_kind": "http_5xx",
              "last_http_status": 503,
              "consecutive_failure_count": 3,
              "retries_paused": false,
              "next_retry_at": 1640999000
            }
        """.trimIndent()

        val feed = json.decodeFromString<Feed>(feedJson)
        assertEquals(3, feed.id)
        assertEquals("My Flaky Feed", feed.custom_title)
        assertEquals(7, feed.category_id)
        assertEquals("error", feed.feed_status)
        assertEquals("warn", feed.severity)
        assertEquals("http_5xx", feed.last_error_kind)
        assertEquals(503, feed.last_http_status)
        assertEquals(3, feed.consecutive_failure_count)
        assertEquals(false, feed.retries_paused)
        assertEquals(1640999000L, feed.next_retry_at)
    }

    @Test
    fun decodes_feed_list_response_wrapped_in_ApiResponse() {
        // GET /v1/feeds shape: { "data": [ ...feeds... ] }
        val payload = """
            {
              "data": [
                {
                  "id": 1,
                  "url": "https://example.com/feed.xml",
                  "title": "Example Feed",
                  "custom_title": null,
                  "is_paused": false,
                  "fetch_interval_minutes": 30,
                  "error_count": 0,
                  "last_fetched": 1640995200,
                  "category_id": null,
                  "feed_status": "ok"
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<List<Feed>>>(payload)
        assertEquals(1, response.data.size)
        assertEquals("ok", response.data[0].feed_status)
        assertNull(response.meta)
    }

    @Test
    fun decodes_feed_response_ignoring_server_only_fields() {
        // The server's `Feed` struct (db.rs) also carries etag/last_modified/
        // consecutive_410_count/retry_after/consecutive_not_modified — none of
        // which the client model declares. They must be tolerated.
        val feedJson = """
            {
              "id": 4,
              "url": "https://example.com/feed.xml",
              "title": "Example",
              "custom_title": null,
              "is_paused": false,
              "fetch_interval_minutes": 30,
              "error_count": 0,
              "last_fetched": null,
              "category_id": null,
              "feed_status": "ok",
              "etag": "\"abc123\"",
              "last_modified": "Wed, 21 Oct 2015 07:28:00 GMT",
              "consecutive_410_count": 0,
              "retry_after": null,
              "consecutive_not_modified": 2
            }
        """.trimIndent()

        val feed = json.decodeFromString<Feed>(feedJson)
        assertEquals(4, feed.id)
        assertNull(feed.unread_count)
    }
}
