package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.SyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T11 — SyncEngine loop tests.
 *
 * Exercises the §4.1 sync loop with a [FakeArticleStore] and a mock [FeedApi]
 * (backed by Ktor MockEngine with scripted JSON responses).
 *
 * Covered:
 *  - Upsert-then-delete apply order within each page.
 *  - Cursor advance and persistence across calls.
 *  - `has_more` follow (multi-page drain).
 *  - `full_resync` clears the store and re-backfills from since = 0.
 */
class SyncEngineTest {

    // -- Test infrastructure --------------------------------------------------

    /**
     * Records every mutating call in order, so tests can assert the exact
     * sequence of store operations (upsert, deleteByIds, setCursor, clear).
     */
    private class FakeArticleStore(
        private var storedCursor: Long = 0L,
    ) : ArticleStore {
        /** Ordered log of every mutating operation. */
        val ops = mutableListOf<Op>()

        /** All articles currently in the store, keyed by id. */
        val articles = mutableMapOf<Int, Article>()

        sealed class Op {
            data class Upsert(val ids: List<Int>) : Op()
            data class DeleteByIds(val ids: List<Long>) : Op()
            data class SetCursor(val seq: Long) : Op()
            data object Clear : Op()
        }

        override suspend fun upsert(articles: List<Article>) {
            ops += Op.Upsert(articles.map { it.id })
            for (a in articles) this.articles[a.id] = a
        }

        override suspend fun deleteByIds(ids: List<Long>) {
            ops += Op.DeleteByIds(ids)
            for (id in ids) articles.remove(id.toInt())
        }

        override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> =
            flowOf(emptyList()) // not exercised by SyncEngine

        override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
            flowOf(0) // not exercised by SyncEngine

        override suspend fun cursor(): Long = storedCursor

        override suspend fun setCursor(seq: Long) {
            ops += Op.SetCursor(seq)
            storedCursor = seq
        }

        override suspend fun clear() {
            ops += Op.Clear
            articles.clear()
            storedCursor = 0
        }
    }

    /** Build a [FeedApi] backed by a [MockEngine] that returns [responses] in order. */
    private fun makeApi(responses: List<String>): FeedApi {
        var index = 0
        val engine = MockEngine {
            val body = responses[index++]
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FeedApi(client)
    }

    /** JSON for a delta response with optional articles and deleted_ids. */
    private fun deltaJson(
        articles: List<Article> = emptyList(),
        deletedIds: List<Long> = emptyList(),
        cursor: Long,
        hasMore: Boolean,
    ): String {
        val json = Json { ignoreUnknownKeys = true }
        val articlesJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Article.serializer()),
            articles
        )
        val deletedJson = deletedIds.joinToString(", ")
        return """
            {
              "articles": $articlesJson,
              "deleted_ids": [$deletedJson],
              "cursor": $cursor,
              "has_more": $hasMore
            }
        """.trimIndent()
    }

    /** Shortcut to build a minimal [Article] fixture. */
    private fun article(id: Int, seq: Long, isRead: Boolean = false) = Article(
        id = id,
        feed_id = 1,
        guid = "guid-$id",
        title = "Article $id",
        content = null,
        link = null,
        author = null,
        published = 1700000000L + id,
        is_read = isRead,
        fetched_at = 1700000000L,
        seq = seq,
    )

    // -- Tests ----------------------------------------------------------------

    @Test
    fun single_page_delta_upserts_then_deletes_then_persists_cursor() = runTest {
        val a1 = article(id = 1, seq = 1)
        val a2 = article(id = 2, seq = 2)
        val api = makeApi(listOf(
            deltaJson(articles = listOf(a1, a2), deletedIds = listOf(10L, 11L), cursor = 5, hasMore = false)
        ))
        val store = FakeArticleStore(storedCursor = 0)
        val engine = SyncEngine(api, store)

        engine.sync()

        // Verify apply order: upsert first, then delete, then setCursor.
        assertEquals(3, store.ops.size, "expected 3 ops, got ${store.ops}")
        assertTrue(store.ops[0] is FakeArticleStore.Op.Upsert, "first op should be Upsert")
        assertTrue(store.ops[1] is FakeArticleStore.Op.DeleteByIds, "second op should be DeleteByIds")
        assertTrue(store.ops[2] is FakeArticleStore.Op.SetCursor, "third op should be SetCursor")

        // Verify content.
        assertEquals(listOf(1, 2), (store.ops[0] as FakeArticleStore.Op.Upsert).ids)
        assertEquals(listOf(10L, 11L), (store.ops[1] as FakeArticleStore.Op.DeleteByIds).ids)
        assertEquals(5L, (store.ops[2] as FakeArticleStore.Op.SetCursor).seq)

        // Store should contain the upserted articles.
        assertEquals(2, store.articles.size)
        assertTrue(store.articles.containsKey(1))
        assertTrue(store.articles.containsKey(2))
    }

    @Test
    fun cursor_is_read_from_store_on_start() = runTest {
        // Store already has a cursor from a previous sync.
        var requestedSince: Long? = null
        val engine2 = MockEngine { req ->
            requestedSince = req.url.parameters["since"]?.toLong()
            respond(
                content = deltaJson(cursor = 42, hasMore = false),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = FeedApi(HttpClient(engine2) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })
        val store = FakeArticleStore(storedCursor = 42)
        val syncEngine = SyncEngine(api, store)

        syncEngine.sync()

        assertEquals(42L, requestedSince, "sync should start from the stored cursor")
    }

    @Test
    fun multi_page_drain_follows_has_more() = runTest {
        val a1 = article(id = 1, seq = 1)
        val a2 = article(id = 2, seq = 2)
        val a3 = article(id = 3, seq = 3)

        val api = makeApi(listOf(
            // Page 1: has_more = true
            deltaJson(articles = listOf(a1, a2), cursor = 2, hasMore = true),
            // Page 2: has_more = false (drain complete)
            deltaJson(articles = listOf(a3), deletedIds = listOf(99L), cursor = 5, hasMore = false),
        ))
        val store = FakeArticleStore(storedCursor = 0)
        val engine = SyncEngine(api, store)

        engine.sync()

        // Should have applied two pages: 3 upserts + 1 delete + 1 delete (empty) + 2 setCursors.
        // Page 1: Upsert([1,2]), DeleteByIds([]), SetCursor(2)
        // Page 2: Upsert([3]), DeleteByIds([99]), SetCursor(5)
        assertEquals(6, store.ops.size, "expected 6 ops for 2 pages, got ${store.ops}")

        // Page 1 ops
        assertEquals(FakeArticleStore.Op.Upsert(listOf(1, 2)), store.ops[0])
        assertEquals(FakeArticleStore.Op.DeleteByIds(emptyList()), store.ops[1])
        assertEquals(FakeArticleStore.Op.SetCursor(2), store.ops[2])

        // Page 2 ops
        assertEquals(FakeArticleStore.Op.Upsert(listOf(3)), store.ops[3])
        assertEquals(FakeArticleStore.Op.DeleteByIds(listOf(99L)), store.ops[4])
        assertEquals(FakeArticleStore.Op.SetCursor(5), store.ops[5])

        // Final cursor persisted.
        assertEquals(5L, store.cursor())

        // All 3 articles in the store; id 99 was never inserted, so delete is a no-op.
        assertEquals(3, store.articles.size)
    }

    @Test
    fun cursor_advances_across_pages() = runTest {
        // Track the `since` param of each request to verify cursor advance.
        val seenSince = mutableListOf<Long>()
        var callIndex = 0
        val responses = listOf(
            deltaJson(articles = listOf(article(1, 1)), cursor = 10, hasMore = true),
            deltaJson(articles = listOf(article(2, 2)), cursor = 20, hasMore = true),
            deltaJson(cursor = 25, hasMore = false),
        )
        val engine = MockEngine { req ->
            seenSince += req.url.parameters["since"]!!.toLong()
            val body = responses[callIndex++]
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = FeedApi(HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })
        val store = FakeArticleStore(storedCursor = 0)
        val syncEngine = SyncEngine(api, store)

        syncEngine.sync()

        assertEquals(listOf(0L, 10L, 20L), seenSince,
            "cursor should advance: 0 -> 10 -> 20")
        assertEquals(25L, store.cursor(), "final cursor should be 25")
    }

    @Test
    fun full_resync_clears_store_and_rebackfills_from_zero() = runTest {
        val a1 = article(id = 1, seq = 1)
        val a5 = article(id = 5, seq = 5)
        val a6 = article(id = 6, seq = 6)

        // Track the `since` param to verify the re-backfill starts at 0.
        val seenSince = mutableListOf<Long>()
        var callIndex = 0
        val responses = listOf(
            // Request 1 (since=50): server says full_resync
            """{ "full_resync": true }""",
            // Request 2 (since=0): page 1 of re-backfill
            deltaJson(articles = listOf(a5, a6), cursor = 6, hasMore = true),
            // Request 3 (since=6): page 2 of re-backfill
            deltaJson(articles = listOf(a1), cursor = 10, hasMore = false),
        )
        val engine2 = MockEngine { req ->
            seenSince += req.url.parameters["since"]!!.toLong()
            val body = responses[callIndex++]
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = FeedApi(HttpClient(engine2) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })

        // Pre-populate the store to verify clear() wipes it.
        val store = FakeArticleStore(storedCursor = 50)
        store.upsert(listOf(article(id = 100, seq = 100), article(id = 101, seq = 101)))
        store.ops.clear() // reset the log so we only see SyncEngine's ops

        val syncEngine = SyncEngine(api, store)
        syncEngine.sync()

        // Request sequence: since=50, then since=0 (after clear), then since=6.
        assertEquals(listOf(50L, 0L, 6L), seenSince,
            "after full_resync, sync should restart from since=0")

        // First op after the full_resync response should be Clear.
        assertTrue(store.ops[0] is FakeArticleStore.Op.Clear,
            "first op should be Clear, got ${store.ops[0]}")

        // After re-backfill, store should contain exactly the re-backfilled articles.
        assertEquals(setOf(1, 5, 6), store.articles.keys,
            "store should contain only re-backfilled articles")
        // Pre-existing articles (100, 101) should be gone.
        assertTrue(100 !in store.articles, "pre-existing article 100 should be cleared")
        assertTrue(101 !in store.articles, "pre-existing article 101 should be cleared")

        // Final cursor.
        assertEquals(10L, store.cursor())
    }

    @Test
    fun repeated_full_resync_at_zero_terminates() = runTest {
        val api = makeApi(listOf(
            """{ "full_resync": true }""",
            """{ "full_resync": true }""",
        ))
        val store = FakeArticleStore(storedCursor = 0)
        val engine = SyncEngine(api, store)

        // Should terminate instead of looping forever.
        engine.sync()

        // Guard fires before clear when cursor is already 0 — no ops.
        assertTrue(store.ops.isEmpty())
        assertEquals(0L, store.cursor())
    }

    @Test
    fun full_resync_then_second_full_resync_clears_once_and_stops() = runTest {
        val seenSince = mutableListOf<Long>()
        var callIndex = 0
        val responses = listOf(
            """{ "full_resync": true }""",
            """{ "full_resync": true }""",
        )
        val engine2 = MockEngine { req ->
            seenSince += req.url.parameters["since"]!!.toLong()
            val body = responses[callIndex++]
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = FeedApi(HttpClient(engine2) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })
        val store = FakeArticleStore(storedCursor = 50)
        store.upsert(listOf(article(id = 1, seq = 1)))
        store.ops.clear()

        val syncEngine = SyncEngine(api, store)
        syncEngine.sync()

        // First request at since=50 triggers clear+reset, second at since=0 breaks.
        assertEquals(listOf(50L, 0L), seenSince)
        assertEquals(listOf<FakeArticleStore.Op>(FakeArticleStore.Op.Clear), store.ops)
        assertTrue(store.articles.isEmpty())
    }

    @Test
    fun exception_mid_pagination_preserves_cursor() = runTest {
        val api = makeApi(listOf(
            deltaJson(articles = listOf(article(1, 1)), cursor = 10, hasMore = true),
        ))
        val store = FakeArticleStore(storedCursor = 0)
        val engine = SyncEngine(api, store)

        assertFailsWith<Exception> { engine.sync() }

        assertEquals(10L, store.cursor())
    }

    @Test
    fun empty_delta_is_a_noop() = runTest {
        val api = makeApi(listOf(
            deltaJson(cursor = 0, hasMore = false)
        ))
        val store = FakeArticleStore(storedCursor = 0)
        val engine = SyncEngine(api, store)

        engine.sync()

        // Upsert and deleteByIds are still called (with empty lists), plus setCursor.
        assertEquals(3, store.ops.size)
        assertEquals(FakeArticleStore.Op.Upsert(emptyList()), store.ops[0])
        assertEquals(FakeArticleStore.Op.DeleteByIds(emptyList()), store.ops[1])
        assertEquals(FakeArticleStore.Op.SetCursor(0), store.ops[2])

        // Store remains empty.
        assertTrue(store.articles.isEmpty())
    }

    @Test
    fun upsert_overwrites_existing_article() = runTest {
        // Article 1 starts unread, then a later sync page delivers it as read.
        val original = article(id = 1, seq = 1, isRead = false)
        val updated = article(id = 1, seq = 5, isRead = true)

        val api = makeApi(listOf(
            deltaJson(articles = listOf(original), cursor = 1, hasMore = true),
            deltaJson(articles = listOf(updated), cursor = 5, hasMore = false),
        ))
        val store = FakeArticleStore(storedCursor = 0)
        val engine = SyncEngine(api, store)

        engine.sync()

        // Only one article in the store — the upserted (updated) version.
        assertEquals(1, store.articles.size)
        assertTrue(store.articles[1]!!.is_read, "article should be marked read after upsert")
        assertEquals(5L, store.articles[1]!!.seq)
    }

    @Test
    fun delete_removes_previously_upserted_article() = runTest {
        // Page 1: upsert article 7.
        // Page 2: delete article 7 (it was removed server-side between pages).
        val a7 = article(id = 7, seq = 3)

        val api = makeApi(listOf(
            deltaJson(articles = listOf(a7), cursor = 3, hasMore = true),
            deltaJson(deletedIds = listOf(7L), cursor = 8, hasMore = false),
        ))
        val store = FakeArticleStore(storedCursor = 0)
        val engine = SyncEngine(api, store)

        engine.sync()

        assertTrue(7 !in store.articles, "article 7 should be deleted")
        assertEquals(8L, store.cursor())
    }

    @Test
    fun page_size_is_forwarded_to_api() = runTest {
        var seenLimit: String? = null
        val engine2 = MockEngine { req ->
            seenLimit = req.url.parameters["limit"]
            respond(
                content = deltaJson(cursor = 0, hasMore = false),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val api = FeedApi(HttpClient(engine2) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })
        val store = FakeArticleStore(storedCursor = 0)
        val syncEngine = SyncEngine(api, store, pageSize = 250)

        syncEngine.sync()

        assertEquals("250", seenLimit, "pageSize should be forwarded as the limit param")
    }
}
