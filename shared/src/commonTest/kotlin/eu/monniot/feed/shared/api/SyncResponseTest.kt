package eu.monniot.feed.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Matches the Json config used by the Ktor HTTP client (ignoreUnknownKeys = true).
private val json = Json { ignoreUnknownKeys = true }

/**
 * Contract tests: the client [SyncResponse] model must decode the two JSON shapes
 * the server emits for `GET /v1/sync`:
 *
 * 1. A **delta** body with `articles`, `deleted_ids`, `cursor`, `has_more`.
 * 2. A **full resync** signal: `{ "full_resync": true }`.
 *
 * Extends the #24 contract-test pattern (see [ArticleModelTest]).
 */
class SyncResponseTest {

    @Test
    fun decodes_delta_response() {
        val body = """
            {
              "articles": [
                {
                  "id": 1,
                  "feed_id": 2,
                  "guid": "https://example.com/a1",
                  "title": "First",
                  "content": "<p>Body</p>",
                  "link": "https://example.com/a1",
                  "author": null,
                  "published": 1779019200,
                  "is_read": false,
                  "fetched_at": 1779031566,
                  "seq": 10
                },
                {
                  "id": 2,
                  "feed_id": 2,
                  "guid": "https://example.com/a2",
                  "title": "Second",
                  "content": null,
                  "link": null,
                  "author": "Bob",
                  "published": null,
                  "is_read": true,
                  "fetched_at": null,
                  "seq": 11
                }
              ],
              "deleted_ids": [5, 8, 13],
              "cursor": 42,
              "has_more": true
            }
        """.trimIndent()

        val response = json.decodeFromString<SyncResponse>(body)

        assertTrue(response is SyncResponse.Delta, "expected Delta, got $response")
        val delta = response as SyncResponse.Delta
        assertEquals(2, delta.articles.size)
        assertEquals(listOf(5, 8, 13), delta.deletedIds)
        assertEquals(42L, delta.cursor)
        assertEquals(true, delta.hasMore)

        // Verify the first article decoded correctly, including seq
        val first = delta.articles[0]
        assertEquals(1, first.id)
        assertEquals(2, first.feed_id)
        assertEquals("First", first.title)
        assertEquals(10L, first.seq)

        // Verify the second article
        val second = delta.articles[1]
        assertEquals(2, second.id)
        assertEquals(true, second.is_read)
        assertEquals(11L, second.seq)
    }

    @Test
    fun decodes_delta_with_empty_articles_and_deletions() {
        val body = """
            {
              "articles": [],
              "deleted_ids": [],
              "cursor": 100,
              "has_more": false
            }
        """.trimIndent()

        val response = json.decodeFromString<SyncResponse>(body)

        assertTrue(response is SyncResponse.Delta, "expected Delta, got $response")
        val delta = response as SyncResponse.Delta
        assertEquals(0, delta.articles.size)
        assertEquals(emptyList(), delta.deletedIds)
        assertEquals(100L, delta.cursor)
        assertEquals(false, delta.hasMore)
    }

    @Test
    fun decodes_full_resync_signal() {
        val body = """{ "full_resync": true }"""

        val response = json.decodeFromString<SyncResponse>(body)

        assertTrue(response is SyncResponse.FullResync, "expected FullResync, got $response")
    }

    @Test
    fun full_resync_takes_precedence_over_other_fields() {
        // The client treats the *presence* of full_resync as the signal regardless
        // of other fields (design plan section 3.3).
        val body = """
            {
              "full_resync": true,
              "articles": [],
              "deleted_ids": [],
              "cursor": 0,
              "has_more": false
            }
        """.trimIndent()

        val response = json.decodeFromString<SyncResponse>(body)

        assertTrue(response is SyncResponse.FullResync,
            "full_resync must take precedence even when delta fields are present")
    }

    @Test
    fun delta_round_trip_serialization() {
        val original = SyncResponse.Delta(
            articles = listOf(
                Article(
                    id = 1,
                    feed_id = 2,
                    guid = "guid-1",
                    title = "Test",
                    content = "Body",
                    link = "https://example.com",
                    author = null,
                    published = 1779019200L,
                    is_read = false,
                    fetched_at = 1779031566L,
                    seq = 5,
                )
            ),
            deletedIds = listOf(3, 7),
            cursor = 15,
            hasMore = false,
        )

        val encoded = json.encodeToString(SyncResponse.serializer(), original)
        val decoded = json.decodeFromString<SyncResponse>(encoded)

        assertTrue(decoded is SyncResponse.Delta, "round-trip must produce Delta")
        val delta = decoded as SyncResponse.Delta
        assertEquals(original.articles.size, delta.articles.size)
        assertEquals(original.deletedIds, delta.deletedIds)
        assertEquals(original.cursor, delta.cursor)
        assertEquals(original.hasMore, delta.hasMore)
        assertEquals(original.articles[0].seq, delta.articles[0].seq)
    }

    @Test
    fun full_resync_round_trip_serialization() {
        val original: SyncResponse = SyncResponse.FullResync

        val encoded = json.encodeToString(SyncResponse.serializer(), original)
        val decoded = json.decodeFromString<SyncResponse>(encoded)

        assertTrue(decoded is SyncResponse.FullResync, "round-trip must produce FullResync")
    }

    @Test
    fun article_without_seq_defaults_to_zero() {
        // Backward compat: servers that don't include seq yet must still decode.
        val body = """
            {
              "articles": [
                {
                  "id": 1,
                  "feed_id": 2,
                  "guid": "https://example.com/old",
                  "title": "Old article",
                  "content": null,
                  "link": null,
                  "author": null,
                  "published": null,
                  "is_read": false,
                  "fetched_at": null
                }
              ],
              "deleted_ids": [],
              "cursor": 1,
              "has_more": false
            }
        """.trimIndent()

        val response = json.decodeFromString<SyncResponse>(body)

        assertTrue(response is SyncResponse.Delta)
        val delta = response as SyncResponse.Delta
        assertEquals(0L, delta.articles[0].seq, "seq must default to 0 when absent")
    }
}
