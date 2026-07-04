package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * #24 contract tests: the client [OpmlImportResult]/[OpmlFeedResult] models
 * must decode the shape emitted by `POST /v1/feeds/import/opml`
 * (server/src/api/types.rs OpmlImportResult/OpmlFeedResult/OpmlFeedStatus).
 *
 * [decodes_feed_result_with_null_title] pins a type-contract fix found while
 * writing these tests: the server's `OpmlFeedResult.title` is `Option<String>`
 * (derived from `outline.title.or(Some(outline.text))` in
 * `import_opml_handler`, both OPML attributes being optional) but the client
 * model declared `title: String` (non-nullable). In practice the server never
 * sends `"title": null` — `Option::or(Some(x))` can't yield `None`, and the
 * opml crate defaults a missing `text` attribute to `""` — so the reachable
 * degenerate case is an empty string, not null; that was already handled by
 * `ifBlank` at the UI call sites. Still, aligning the client type with the
 * server's `Option<String>` guards against future drift (e.g. a
 * `skip_serializing_if` added to `title` as already exists on the sibling
 * `error`/`category` fields), so the client field was made `String?`.
 */
class OpmlImportModelTest {

    @Test
    fun decodes_typical_import_result() {
        val payload = """
            {
              "data": {
                "total_feeds": 4,
                "imported": 2,
                "already_exists": 1,
                "failed": 1,
                "categories_created": 1,
                "feeds": [
                  {
                    "url": "https://example.com/feed1.xml",
                    "title": "Feed One",
                    "status": "imported",
                    "category": "Tech"
                  },
                  {
                    "url": "https://example.com/feed2.xml",
                    "title": "Feed Two",
                    "status": "already_exists"
                  },
                  {
                    "url": "https://broken.example.com/feed3.xml",
                    "title": "Broken Feed",
                    "status": "failed",
                    "error": "Failed to parse feed: invalid XML"
                  }
                ]
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<OpmlImportResult>>(payload)
        val result = response.data

        assertEquals(4, result.total_feeds)
        assertEquals(2, result.imported)
        assertEquals(1, result.already_exists)
        assertEquals(1, result.failed)
        assertEquals(1, result.categories_created)
        assertEquals(3, result.feeds.size)

        assertEquals("imported", result.feeds[0].status)
        assertEquals("Tech", result.feeds[0].category)
        assertNull(result.feeds[0].error)

        assertEquals("already_exists", result.feeds[1].status)
        assertNull(result.feeds[1].category)

        assertEquals("failed", result.feeds[2].status)
        assertEquals("Failed to parse feed: invalid XML", result.feeds[2].error)
    }

    @Test
    fun decodes_feed_result_with_null_title() {
        // The server currently never sends this (see class doc), but the client
        // field is Option<String>-aligned and must decode a null title without
        // throwing regardless.
        val payload = """
            {
              "data": {
                "total_feeds": 1,
                "imported": 0,
                "already_exists": 0,
                "failed": 1,
                "categories_created": 0,
                "feeds": [
                  {
                    "url": "https://untitled.example.com/feed.xml",
                    "title": null,
                    "status": "failed",
                    "error": "Failed to fetch feed"
                  }
                ]
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<OpmlImportResult>>(payload)
        val feed = response.data.feeds.single()
        assertNull(feed.title)
        assertEquals("https://untitled.example.com/feed.xml", feed.url)
        assertEquals("failed", feed.status)
    }

    @Test
    fun decodes_empty_import_result() {
        val payload = """
            {
              "data": {
                "total_feeds": 0,
                "imported": 0,
                "already_exists": 0,
                "failed": 0,
                "categories_created": 0,
                "feeds": []
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ApiResponse<OpmlImportResult>>(payload)
        assertEquals(0, response.data.total_feeds)
        assertEquals(0, response.data.feeds.size)
    }
}
