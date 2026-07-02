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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

        override fun observeTotalCount(): Flow<Int> =
            flowOf(0) // not exercised by SyncEngine

        override suspend fun cursor(): Long = storedCursor

        override suspend fun setCursor(seq: Long) {
            ops += Op.SetCursor(seq)
            storedCursor = seq
        }

        override suspend fun markRead(id: Int, isRead: Boolean) {
            articles[id]?.let { articles[id] = it.copy(is_read = isRead) }
        }

        override suspend fun deleteByFeedId(feedId: Int) {
            articles.entries.removeAll { it.value.feed_id == feedId }
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

    // -- BUG-33: Concurrency tests -------------------------------------------

    /**
     * A [FakeArticleStore] variant that suspends inside [upsert] on a gate,
     * allowing a test to interleave two concurrent [SyncEngine.sync] calls.
     */
    private class GatedArticleStore(
        private var storedCursor: Long = 0L,
    ) : ArticleStore {
        /** Ordered log of every mutating operation (including which cursor was read). */
        val ops = mutableListOf<String>()

        /** All articles currently in the store, keyed by id. */
        val articles = mutableMapOf<Int, Article>()

        /**
         * When non-null, the **next** [upsert] call will suspend on this gate
         * before applying, then clear the gate. This lets the test start a second
         * sync() while the first is paused inside its upsert.
         */
        var upsertGate: CompletableDeferred<Unit>? = null

        override suspend fun upsert(articles: List<Article>) {
            upsertGate?.let { gate ->
                upsertGate = null
                gate.await() // suspend until the test releases the gate
            }
            ops += "upsert(${articles.map { it.id }})"
            for (a in articles) this.articles[a.id] = a
        }

        override suspend fun deleteByIds(ids: List<Long>) {
            ops += "deleteByIds($ids)"
            for (id in ids) articles.remove(id.toInt())
        }

        override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> =
            flowOf(emptyList())

        override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
            flowOf(0)

        override fun observeTotalCount(): Flow<Int> =
            flowOf(0)

        override suspend fun cursor(): Long {
            ops += "cursor()=$storedCursor"
            return storedCursor
        }

        override suspend fun setCursor(seq: Long) {
            ops += "setCursor($seq)"
            storedCursor = seq
        }

        override suspend fun markRead(id: Int, isRead: Boolean) {
            articles[id]?.let { articles[id] = it.copy(is_read = isRead) }
        }

        override suspend fun deleteByFeedId(feedId: Int) {
            articles.entries.removeAll { it.value.feed_id == feedId }
        }

        override suspend fun clear() {
            ops += "clear()"
            articles.clear()
            storedCursor = 0
        }
    }

    /**
     * BUG-33 regression: two concurrent sync() calls must not double-apply
     * pages or persist a stale cursor.
     *
     * Setup: the API serves two pages (page A cursor=10, page B cursor=20).
     * Caller 1 starts sync(), suspends mid-upsert (on a gate). Caller 2
     * starts sync() — the mutex should make it wait. When the gate is
     * released, caller 1 finishes normally. Caller 2 then runs and reads
     * the now-advanced cursor (20), gets an empty delta, and finishes.
     *
     * Assertions:
     * - Each page is applied exactly once.
     * - The final persisted cursor is correct (20).
     * - No article is upserted twice.
     */
    @Test
    fun concurrent_sync_calls_are_serialized_by_mutex() = runTest {
        val gate = CompletableDeferred<Unit>()

        // Track request count to serve different responses.
        var requestCount = 0
        val engine2 = MockEngine { req ->
            val since = req.url.parameters["since"]!!.toLong()
            requestCount++
            val body = when {
                // Caller 1, page 1: articles [1,2], cursor=10, has_more
                since == 0L && requestCount == 1 ->
                    deltaJson(articles = listOf(article(1, 1), article(2, 2)), cursor = 10, hasMore = true)
                // Caller 1, page 2: articles [3], cursor=20, done
                since == 10L ->
                    deltaJson(articles = listOf(article(3, 3)), cursor = 20, hasMore = false)
                // Caller 2 (after caller 1 finished): cursor is now 20, empty delta
                since == 20L ->
                    deltaJson(cursor = 20, hasMore = false)
                // Fallback: if since=0 again it means the mutex didn't work (double-read)
                else ->
                    deltaJson(cursor = since, hasMore = false)
            }
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

        val store = GatedArticleStore(storedCursor = 0)
        store.upsertGate = gate // First upsert will suspend on this gate
        val syncEngine = SyncEngine(api, store)

        // Launch two concurrent sync() calls.
        val job1 = async { syncEngine.sync() }
        val job2 = async { syncEngine.sync() }

        // Let the test dispatcher run enough to start job1's first API call
        // and have it suspend on the gate inside upsert.
        // job2 should be waiting on the mutex, not issuing its own API call.
        testScheduler.advanceUntilIdle()

        // Release the gate — caller 1 finishes its upsert and continues.
        gate.complete(Unit)

        // Let both jobs complete.
        job1.await()
        job2.await()

        // Each article should appear exactly once.
        assertEquals(3, store.articles.size, "expected 3 articles, got ${store.articles.keys}")
        assertTrue(store.articles.containsKey(1))
        assertTrue(store.articles.containsKey(2))
        assertTrue(store.articles.containsKey(3))

        // Final cursor should be 20 (set by caller 1; caller 2 sees 20 and gets empty delta).
        assertEquals(20L, store.storedCursorValue(),
            "final cursor should be 20")

        // The ops log should show caller 1 running to completion, then caller 2.
        // Caller 1: cursor()=0, upsert([1,2]), deleteByIds([]), setCursor(10),
        //           upsert([3]), deleteByIds([]), setCursor(20)
        // Caller 2: cursor()=20, upsert([]), deleteByIds([]), setCursor(20)
        // Key: cursor()=0 appears exactly once (no double-read at 0).
        val cursorReads = store.ops.filter { it.startsWith("cursor()=") }
        assertEquals(2, cursorReads.size, "expected 2 cursor reads (one per sync call), got $cursorReads")
        assertEquals("cursor()=0", cursorReads[0], "caller 1 should read cursor=0")
        assertEquals("cursor()=20", cursorReads[1], "caller 2 should read cursor=20 (advanced by caller 1)")

        // Upsert of articles [1,2] should appear exactly once.
        val upsertOps = store.ops.filter { it.startsWith("upsert(") }
        val allUpsertedIds = upsertOps.flatMap { op ->
            // Parse "upsert([1, 2])" → [1, 2]
            op.removePrefix("upsert(").removeSuffix(")").removeSurrounding("[", "]")
                .split(", ").filter { it.isNotEmpty() }.map { it.trim().toInt() }
        }
        assertEquals(listOf(1, 2, 3), allUpsertedIds.filter { it != 0 }.sorted(),
            "articles 1, 2, 3 should each be upserted exactly once")
    }

    /** Helper to read the stored cursor without adding to the ops log. */
    private suspend fun GatedArticleStore.storedCursorValue(): Long = cursor().also {
        // Remove the cursor() read we just added to avoid polluting assertions.
        ops.removeAt(ops.lastIndex)
    }

    /**
     * BUG-33 follow-up: verify the second sync call picks up the
     * first call's cursor and does not re-fetch already-applied pages.
     */
    @Test
    fun second_sync_resumes_from_advanced_cursor() = runTest {
        val seenSince = mutableListOf<Long>()
        var callIndex = 0
        val responses = listOf(
            // Caller 1: page 1 → cursor=10, has_more=true
            deltaJson(articles = listOf(article(1, 1)), cursor = 10, hasMore = true),
            // Caller 1: page 2 → cursor=20, done
            deltaJson(articles = listOf(article(2, 2)), cursor = 20, hasMore = false),
            // Caller 2: starts at since=20, gets empty delta
            deltaJson(cursor = 20, hasMore = false),
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
        val store = GatedArticleStore(storedCursor = 0)
        val syncEngine = SyncEngine(api, store)

        // Run two sync() calls sequentially (mutex serializes them).
        val job1 = async { syncEngine.sync() }
        val job2 = async { syncEngine.sync() }
        job1.await()
        job2.await()

        // Caller 1: since=0, since=10 (two pages).
        // Caller 2: since=20 (cursor advanced by caller 1).
        assertEquals(listOf(0L, 10L, 20L), seenSince,
            "caller 2 should start from the cursor caller 1 advanced to")
        assertEquals(20L, store.storedCursorValue())
    }
}
