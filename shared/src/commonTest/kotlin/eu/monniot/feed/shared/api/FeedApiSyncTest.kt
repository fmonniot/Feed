package eu.monniot.feed.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mock-engine tests for [FeedApi.sync]: verifies that the method
 *  - issues `GET /v1/sync` with the `since` and optional `limit` query params,
 *  - decodes a delta response into [SyncResponse.Delta], and
 *  - decodes a full-resync response into [SyncResponse.FullResync].
 *
 * Follows the same pattern as [FeedApiRefreshTest].
 */
class FeedApiSyncTest {

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
    fun sync_issues_GET_with_since_param() = runTest {
        var seenPath: String? = null
        var seenMethod: HttpMethod? = null
        var seenQuery: String? = null
        val engine = MockEngine { req ->
            seenPath = req.url.encodedPath
            seenMethod = req.method
            seenQuery = req.url.encodedQuery
            respond(
                content = """
                    {
                      "articles": [],
                      "deleted_ids": [],
                      "cursor": 0,
                      "has_more": false
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        api.sync(since = 42)

        assertEquals(HttpMethod.Get, seenMethod, "must GET /v1/sync")
        assertTrue(seenPath!!.endsWith("v1/sync"), "path was $seenPath")
        assertTrue(seenQuery!!.contains("since=42"), "query was $seenQuery")
    }

    @Test
    fun sync_includes_limit_when_provided() = runTest {
        var seenQuery: String? = null
        val engine = MockEngine { req ->
            seenQuery = req.url.encodedQuery
            respond(
                content = """
                    {
                      "articles": [],
                      "deleted_ids": [],
                      "cursor": 0,
                      "has_more": false
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        api.sync(since = 0, limit = 100)

        assertTrue(seenQuery!!.contains("since=0"), "query was $seenQuery")
        assertTrue(seenQuery!!.contains("limit=100"), "query was $seenQuery")
    }

    @Test
    fun sync_omits_limit_when_null() = runTest {
        var seenQuery: String? = null
        val engine = MockEngine { req ->
            seenQuery = req.url.encodedQuery
            respond(
                content = """
                    {
                      "articles": [],
                      "deleted_ids": [],
                      "cursor": 0,
                      "has_more": false
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        api.sync(since = 0)

        assertTrue(!seenQuery!!.contains("limit"), "limit must not appear in query: $seenQuery")
    }

    @Test
    fun sync_decodes_delta_response() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "articles": [
                        {
                          "id": 1,
                          "feed_id": 2,
                          "guid": "https://example.com/a1",
                          "title": "Hello",
                          "content": "<p>World</p>",
                          "link": "https://example.com/a1",
                          "author": null,
                          "published": 1779019200,
                          "is_read": false,
                          "fetched_at": 1779031566,
                          "seq": 7
                        }
                      ],
                      "deleted_ids": [3, 5],
                      "cursor": 7,
                      "has_more": true
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        val result = api.sync(since = 0)

        assertTrue(result is SyncResponse.Delta, "expected Delta, got $result")
        val delta = result as SyncResponse.Delta
        assertEquals(1, delta.articles.size)
        assertEquals("Hello", delta.articles[0].title)
        assertEquals(7L, delta.articles[0].seq)
        assertEquals(listOf(3L, 5L), delta.deletedIds)
        assertEquals(7L, delta.cursor)
        assertEquals(true, delta.hasMore)
    }

    @Test
    fun sync_decodes_full_resync_response() = runTest {
        val engine = MockEngine {
            respond(
                content = """{ "full_resync": true }""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = makeApi(engine)

        val result = api.sync(since = 99999)

        assertTrue(result is SyncResponse.FullResync,
            "expected FullResync, got $result")
    }
}
