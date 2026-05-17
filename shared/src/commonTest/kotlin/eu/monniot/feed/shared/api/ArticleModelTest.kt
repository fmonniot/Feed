package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * Contract tests: the client Article model must decode exactly what the server emits.
 *
 * The server (server/src/db.rs Article struct) serialises:
 *   id, feed_id, guid, title?, content?, link?, author?, published?,
 *   is_read, is_starred, starred_at?, fetched_at?
 *
 * It does NOT serialise read_at or rank (those fields don't exist in the struct).
 * Extra server fields (e.g. etag, last_modified on Feed) must be tolerated by
 * ignoreUnknownKeys — that's covered implicitly by the last test below.
 */
class ArticleModelTest {

    @Test
    fun decodes_typical_server_response() {
        val articleJson = """
            {
              "id": 1,
              "feed_id": 1,
              "guid": "https://example.com/article-1",
              "title": "Test Article",
              "content": "<p>Body text</p>",
              "link": "https://example.com/article-1",
              "published": 1779019200,
              "is_read": false,
              "is_starred": false,
              "starred_at": null,
              "fetched_at": 1779031566,
              "author": "Alice"
            }
        """.trimIndent()

        val article = json.decodeFromString<Article>(articleJson)

        assertEquals(1, article.id)
        assertEquals(1, article.feed_id)
        assertEquals("Test Article", article.title)
        assertEquals("<p>Body text</p>", article.content)
        assertEquals("https://example.com/article-1", article.link)
        assertEquals(1779019200L, article.published)
        assertEquals(false, article.is_read)
        assertEquals(false, article.is_starred)
        assertNull(article.starred_at)
        assertEquals(1779031566L, article.fetched_at)
        assertEquals("Alice", article.author)
    }

    @Test
    fun decodes_without_read_at_field() {
        // The server never emits read_at. This was the original bug: the model
        // declared it as a required field and MissingFieldException was swallowed.
        val articleJson = """
            {
              "id": 2,
              "feed_id": 3,
              "guid": "https://example.com/no-read-at",
              "title": "No read_at",
              "content": "Some content",
              "link": "https://example.com/no-read-at",
              "published": 1779000000,
              "is_read": true,
              "is_starred": false,
              "starred_at": null,
              "fetched_at": null,
              "author": null
            }
        """.trimIndent()

        val article = json.decodeFromString<Article>(articleJson)
        assertEquals(2, article.id)
    }

    @Test
    fun decodes_nullable_fields_as_null() {
        // title, content, link, published can be null for feeds that omit them.
        val articleJson = """
            {
              "id": 3,
              "feed_id": 5,
              "guid": "https://example.com/minimal",
              "title": null,
              "content": null,
              "link": null,
              "published": null,
              "is_read": false,
              "is_starred": false,
              "starred_at": null,
              "fetched_at": null,
              "author": null
            }
        """.trimIndent()

        val article = json.decodeFromString<Article>(articleJson)
        assertNull(article.title)
        assertNull(article.content)
        assertNull(article.link)
        assertNull(article.published)
    }

    @Test
    fun decodes_ignoring_unknown_server_fields() {
        // The server may add new fields; they must not break deserialization.
        val articleJson = """
            {
              "id": 4,
              "feed_id": 1,
              "guid": "https://example.com/future",
              "title": "Future article",
              "content": "Content",
              "link": "https://example.com/future",
              "published": 1779000000,
              "is_read": false,
              "is_starred": false,
              "starred_at": null,
              "fetched_at": null,
              "author": null,
              "some_future_field": "ignored",
              "another_future_field": 42
            }
        """.trimIndent()

        val article = json.decodeFromString<Article>(articleJson)
        assertEquals(4, article.id)
    }
}
