package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.SharedFeedRepository
import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.api.FeedApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Acceptance tests for ticket #107 (FU-2 — Offline read-state mutation queue).
 *
 * Covers all three acceptance criteria:
 *
 * 1. **Offline mark:** marking read/unread while offline persists locally and
 *    the PUT fires on the next [SyncEngine.sync] / [SharedFeedRepository.refresh] call.
 * 2. **Guard:** a sync pull while a local change is un-acked does NOT revert it.
 * 3. **Persistence:** the queue survives "process death" — a reconstructed store
 *    from the same persistent backing retains pending mutations.
 */
class OfflineMutationQueueTest {

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    /**
     * A persistent-backed in-memory [ArticleStore] for testing process-death
     * simulation.  Two instances that share the same [backing] maps share state,
     * mimicking re-opening the same IndexedDB / Room database after a restart.
     */
    private class PersistentFakeArticleStore(
        // `internal` so tests in the same class can peek at the raw backing for
        // process-death assertions (e.g. backing.articles[id]!!.is_read).
        internal val backing: StoreBacking = StoreBacking(),
    ) : ArticleStore {

        /** Shared mutable state — survives across instance reconstructions. */
        class StoreBacking {
            val articles = mutableMapOf<Int, Article>()
            val mutations = mutableMapOf<Int, Boolean>()
            var cursor: Long = 0L
        }

        private val _signal = MutableStateFlow(0)

        // ---- Write side ----

        override suspend fun upsert(articles: List<Article>) {
            for (a in articles) backing.articles[a.id] = a
            _signal.value++
        }

        override suspend fun deleteByIds(ids: List<Long>) {
            for (id in ids) backing.articles.remove(id.toInt())
            _signal.value++
        }

        override suspend fun markRead(id: Int, isRead: Boolean) {
            backing.articles[id]?.let { backing.articles[id] = it.copy(is_read = isRead) }
            _signal.value++
        }

        override suspend fun deleteByFeedId(feedId: Int) {
            backing.articles.entries.removeAll { it.value.feed_id == feedId }
            _signal.value++
        }

        override suspend fun unreadIds(filter: ArticleFilter): List<Int> =
            backing.articles.values
                .filter { !it.is_read && matchesFilter(it, filter) }
                .map { it.id }

        override suspend fun clear() {
            backing.articles.clear()
            backing.cursor = 0L
            _signal.value++
        }

        // ---- Read side ----

        override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> =
            _signal.map { _ ->
                backing.articles.values
                    .filter { matchesFilter(it, filter) }
                    .sortedWith(compareByDescending<Article> { it.published ?: Long.MIN_VALUE }
                        .thenByDescending { it.seq })
                    .let { sorted ->
                        val start = window.first.coerceAtMost(sorted.size)
                        val end = (window.last + 1).coerceAtMost(sorted.size)
                        sorted.subList(start, end)
                    }
            }.distinctUntilChanged()

        override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
            _signal.map { _ ->
                backing.articles.values.count { !it.is_read && matchesFilter(it, filter) }
            }.distinctUntilChanged()

        override fun observeTotalCount(): Flow<Int> =
            _signal.map { _ -> backing.articles.size }.distinctUntilChanged()

        override fun observeCount(filter: ArticleFilter): Flow<Int> =
            _signal.map { _ ->
                when (filter) {
                    is ArticleFilter.All -> backing.articles.size
                    is ArticleFilter.UnreadOnly -> backing.articles.values.count { !it.is_read }
                    is ArticleFilter.ByFeed -> backing.articles.values.count { it.feed_id == filter.feedId }
                }
            }.distinctUntilChanged()

        // ---- Cursor ----

        override suspend fun cursor(): Long = backing.cursor

        override suspend fun setCursor(seq: Long) { backing.cursor = seq }

        // ---- Offline mutation queue ----

        override suspend fun enqueueMutation(id: Int, isRead: Boolean) {
            backing.mutations[id] = isRead
        }

        override suspend fun dequeueMutation(id: Int, isRead: Boolean) {
            if (backing.mutations[id] == isRead) backing.mutations.remove(id)
        }

        override suspend fun pendingMutations(): Map<Int, Boolean> = backing.mutations.toMap()

        // ---- Helper ----

        private fun matchesFilter(article: Article, filter: ArticleFilter): Boolean = when (filter) {
            is ArticleFilter.All -> true
            is ArticleFilter.UnreadOnly -> !article.is_read || article.id == filter.keepArticleId
            is ArticleFilter.ByFeed -> article.feed_id == filter.feedId
        }
    }

    /** Minimal [Article] fixture. */
    private fun article(id: Int, isRead: Boolean = false, seq: Long = id.toLong()) = Article(
        id = id,
        feed_id = 1,
        guid = "guid-$id",
        title = "Article $id",
        content = null,
        link = null,
        author = null,
        published = 1_700_000_000L + id,
        is_read = isRead,
        fetched_at = null,
        seq = seq,
    )

    /** JSON for a delta sync response (no articles, no deletes — just advances cursor). */
    private fun emptyDelta(cursor: Long = 0L) = """
        {"articles":[],"deleted_ids":[],"cursor":$cursor,"has_more":false}
    """.trimIndent()

    /** JSON for a delta response that delivers one article with a specific is_read state. */
    private fun articleDelta(article: Article, cursor: Long): String {
        val articleJson = enc(article)
        return """{"articles":[$articleJson],"deleted_ids":[],"cursor":$cursor,"has_more":false}"""
    }

    /** Serialize a single [Article] to JSON. */
    private fun enc(a: Article): String =
        Json { ignoreUnknownKeys = true }.encodeToString(Article.serializer(), a)

    /** JSON for an article-read update response (server ack).
     *  Must match [ApiResponse]<[UpdatedCountResponse]> — {"data":{"updated":N}}.
     */
    private val readUpdateAck = """{"data":{"updated":1}}"""

    /** Build a [FeedApi] backed by a [MockEngine] whose request handler is [handler]. */
    private fun makeApi(handler: suspend (url: String) -> Pair<Int, String>): FeedApi {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val (statusCode, responseBody) = handler(path)
            respond(
                content = responseBody,
                status = HttpStatusCode.fromValue(statusCode),
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

    // -----------------------------------------------------------------------
    // Acceptance criterion 1: offline mark persists locally + enqueues;
    // the PUT fires on reconnect (next sync()).
    // -----------------------------------------------------------------------

    /**
     * markAsRead while offline:
     * - local mirror is updated immediately,
     * - a pending mutation is enqueued,
     * - a subsequent SyncEngine.sync() flushes the mutation (PUT fires),
     * - the queue is empty after the flush succeeds.
     */
    @Test
    fun offlineMarkRead_persistsLocallyAndFlushesOnReconnect() = runTest {
        val putCalls = mutableListOf<Pair<String, Boolean>>() // (path, is_read)
        val syncResponses = mutableListOf(emptyDelta(cursor = 0L))
        var syncCallCount = 0

        val api = makeApi { path ->
            when {
                path.contains("/read") -> {
                    // Extract article id from path like "v1/articles/42/read"
                    val id = path.split("/").dropLast(1).last()
                    putCalls.add(Pair(id, true))
                    200 to readUpdateAck
                }
                path.endsWith("v1/sync") -> {
                    val resp = syncResponses.getOrElse(syncCallCount) { emptyDelta() }
                    syncCallCount++
                    200 to resp
                }
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }

        val backing = PersistentFakeArticleStore.StoreBacking()
        val store = PersistentFakeArticleStore(backing)
        // Seed the store with one unread article.
        store.upsert(listOf(article(id = 1, isRead = false)))

        // --- Simulate offline: markAsRead fails the PUT ---
        // Build a separate "offline" api that always fails network calls.
        val offlineApi = makeApi { path ->
            when {
                path.contains("/read") -> 500 to """{}"""  // simulates network failure
                path.endsWith("v1/sync") -> 500 to """{}"""
                else -> 500 to """{}"""
            }
        }
        val offlineEngine = SyncEngine(offlineApi, store)
        val offlineRepo = SharedFeedRepository(offlineApi, store, offlineEngine)

        // markAsRead with offline api — PUT fails but local state is updated.
        offlineRepo.markAsRead(1)

        // Local state updated immediately.
        assertTrue(backing.articles[1]!!.is_read, "local mirror must be updated immediately")
        // Pending mutation enqueued (still there because PUT failed).
        assertEquals(mapOf(1 to true), backing.mutations,
            "pending mutation must be enqueued after offline PUT failure")

        // --- Simulate reconnect: use the working api ---
        val reconnectEngine = SyncEngine(api, store)
        syncResponses.add(emptyDelta(cursor = 0L)) // provide one more sync response

        reconnectEngine.sync()

        // The batched read call was fired during flush and the queue drained.
        // (The flush now posts to /v1/articles/read with the ids in the body,
        // so the specific id is asserted via the drained queue, not the path.)
        assertTrue(putCalls.isNotEmpty(), "a read call must be issued during sync on reconnect")
        assertTrue(backing.mutations.isEmpty(), "pending mutation must be removed after ack")
    }

    /**
     * markAsUnread while online goes through immediately — no pending mutation remains.
     */
    @Test
    fun onlineMarkAsUnread_dequeuesImmediately() = runTest {
        val api = makeApi { path ->
            when {
                path.contains("/read") -> 200 to readUpdateAck
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }

        val store = PersistentFakeArticleStore()
        store.upsert(listOf(article(id = 5, isRead = true)))

        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        repo.markAsUnread(5)

        assertFalse(store.backing.articles[5]!!.is_read, "local mirror must be unread")
        assertTrue(store.backing.mutations.isEmpty(),
            "mutation must be dequeued immediately after successful online PUT")
    }

    // -----------------------------------------------------------------------
    // Acceptance criterion 2: sync pull while un-acked does NOT revert local change.
    // -----------------------------------------------------------------------

    /**
     * Guard: when article 1 has an un-acked pending mutation (isRead=true),
     * a sync pull that delivers article 1 with isRead=false must NOT overwrite it.
     */
    @Test
    fun syncPullDoesNotRevertUnackedLocalChange() = runTest {
        // Article 1 starts unread on the server (seq 1).
        val serverArticleUnread = article(id = 1, isRead = false, seq = 1)
        // The server delta that arrives during pull still returns it as unread
        // (our PUT hasn't been processed yet / arrived out of order).
        val serverArticleStillUnread = article(id = 1, isRead = false, seq = 2)

        var syncCallCount = 0
        val syncResponses = listOf(
            // First sync: get the unread article.
            articleDelta(serverArticleUnread, cursor = 1L),
            // Second sync: server still shows unread (our PUT hasn't arrived yet).
            articleDelta(serverArticleStillUnread, cursor = 2L),
        )

        // This api NEVER successfully responds to PUT /articles/*/read
        // (simulating network failure), so the mutation stays queued.
        val api = makeApi { path ->
            when {
                path.contains("/read") -> 500 to """{}"""  // network failure
                path.endsWith("v1/sync") -> {
                    val resp = syncResponses.getOrElse(syncCallCount) { emptyDelta() }
                    syncCallCount++
                    200 to resp
                }
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }

        val store = PersistentFakeArticleStore()
        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        // Step 1: initial sync — loads article 1 as unread.
        engine.sync()
        assertFalse(store.backing.articles[1]!!.is_read, "initial state: article 1 is unread")

        // Step 2: user marks article 1 as read while offline (PUT fails).
        repo.markAsRead(1)
        assertTrue(store.backing.articles[1]!!.is_read, "local state: article 1 is now read")
        assertEquals(mapOf(1 to true), store.backing.mutations, "mutation is queued")

        // Step 3: sync is triggered (reconnect); server still returns article 1 as unread.
        // The guard must block the upsert of article 1 (still pending).
        engine.sync()

        assertTrue(store.backing.articles[1]!!.is_read,
            "article 1 must remain read — sync pull must NOT revert un-acked local change")
        // Mutation is still queued because the flush also failed.
        assertEquals(mapOf(1 to true), store.backing.mutations,
            "mutation must remain queued when flush fails")
    }

    /**
     * Guard does NOT affect articles without pending mutations.
     * Other articles in the same sync pull are upserted normally.
     */
    @Test
    fun syncGuardOnlyBlocksPendingArticles() = runTest {
        val a1Unread = article(id = 1, isRead = false, seq = 1)
        val a2Read = article(id = 2, isRead = true, seq = 2)
        // Server will return both articles in the delta, but article 1 is in the pending queue.
        val json = Json { ignoreUnknownKeys = true }
        val deltaJson = """
            {
              "articles": [
                ${json.encodeToString(Article.serializer(), a1Unread)},
                ${json.encodeToString(Article.serializer(), a2Read)}
              ],
              "deleted_ids": [],
              "cursor": 10,
              "has_more": false
            }
        """.trimIndent()

        val api = makeApi { path ->
            when {
                path.contains("/read") -> 500 to """{}"""  // flush always fails
                path.endsWith("v1/sync") -> 200 to deltaJson
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }

        val store = PersistentFakeArticleStore()
        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        // Seed article 1 as unread.
        store.upsert(listOf(article(id = 1, isRead = false)))
        // Locally mark article 1 as read (PUT fails → stays queued).
        repo.markAsRead(1)
        assertTrue(store.backing.articles[1]!!.is_read, "local: article 1 is read")

        // Sync: delivers both articles from server.  Only article 1 is guarded.
        engine.sync()

        // Article 1 local change preserved (guard worked).
        assertTrue(store.backing.articles[1]!!.is_read,
            "article 1 must remain read (pending guard)")
        // Article 2 (no pending mutation) is applied normally.
        assertTrue(store.backing.articles[2]!!.is_read,
            "article 2 must be upserted normally (no pending mutation)")
    }

    // -----------------------------------------------------------------------
    // Acceptance criterion 3: queue survives "process death".
    // -----------------------------------------------------------------------

    /**
     * Process-death simulation: enqueue a mutation, then construct a NEW store
     * instance from the SAME persistent backing, and verify the mutation is
     * still present.
     */
    @Test
    fun mutationQueueSurvivesProcessDeath() = runTest {
        val backing = PersistentFakeArticleStore.StoreBacking()

        // "Session 1": enqueue a mutation.
        val store1 = PersistentFakeArticleStore(backing)
        store1.enqueueMutation(id = 42, isRead = true)
        assertEquals(mapOf(42 to true), store1.pendingMutations(), "queue present in session 1")

        // Simulate process death: discard store1, construct store2 from same backing.
        val store2 = PersistentFakeArticleStore(backing)
        assertEquals(mapOf(42 to true), store2.pendingMutations(),
            "queue must survive process death — same backing must retain mutations")
    }

    /**
     * After process death, the SyncEngine built on the reconstructed store
     * picks up the queued mutations and flushes them on the first sync().
     */
    @Test
    fun engineFlushesMutationsAfterProcessDeath() = runTest {
        val backing = PersistentFakeArticleStore.StoreBacking()

        // "Session 1": seed article, mark as read offline (PUT fails).
        val offlineApi = makeApi { _ -> 500 to """{}""" }
        val store1 = PersistentFakeArticleStore(backing)
        store1.upsert(listOf(article(id = 7, isRead = false)))
        val offlineEngine = SyncEngine(offlineApi, store1)
        val offlineRepo = SharedFeedRepository(offlineApi, store1, offlineEngine)
        offlineRepo.markAsRead(7)

        assertTrue(backing.articles[7]!!.is_read, "local state read after offline mark")
        assertEquals(mapOf(7 to true), backing.mutations, "mutation queued")

        // "Session 2": process death — new store from same backing, online api.
        var readCalls = 0
        val onlineApi = makeApi { path ->
            when {
                path.contains("/read") -> {
                    readCalls++
                    200 to readUpdateAck
                }
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }

        val store2 = PersistentFakeArticleStore(backing)
        val onlineEngine = SyncEngine(onlineApi, store2)

        onlineEngine.sync()

        // The batched read call was fired and the mutation dequeued on ack.
        assertTrue(readCalls > 0, "the batched read must be fired after process death recovery")
        assertTrue(backing.mutations.isEmpty(), "mutation dequeued after successful ack")
    }

    // -----------------------------------------------------------------------
    // Pull guard: merge (not skip) so content updates aren't dropped.
    // -----------------------------------------------------------------------

    /**
     * When an article with an un-acked local read-state change is redelivered by
     * the server with edited content, the pull must merge — keep the local
     * `is_read` but apply the new content — rather than skip the whole article
     * (which would drop the content edit until the article next changed).
     */
    @Test
    fun syncPull_mergesServerContentWhilePreservingUnackedReadState() = runTest {
        val updated = article(id = 1, isRead = false, seq = 5).copy(title = "Edited Title")
        val api = makeApi { path ->
            when {
                path.contains("/read") -> 500 to """{}"""  // flush fails → stays queued
                path.endsWith("v1/sync") -> 200 to articleDelta(updated, cursor = 5L)
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        store.upsert(listOf(article(id = 1, isRead = false, seq = 1).copy(title = "Old Title")))
        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        repo.markAsRead(1)  // offline → queued
        assertTrue(store.backing.articles[1]!!.is_read)

        engine.sync()

        val merged = store.backing.articles[1]!!
        assertTrue(merged.is_read, "un-acked local read state must be preserved")
        assertEquals(
            "Edited Title", merged.title,
            "server content update must still be applied (merge, not skip)",
        )
    }

    /**
     * A mutation enqueued *during* an in-flight multi-page pull must be guarded
     * on the later page. This only holds because the pending set is re-read per
     * page rather than snapshotted once before the loop.
     */
    @Test
    fun syncPull_reReadsPendingPerPage_guardsMidSyncMutation() = runTest {
        val a1 = article(id = 1, isRead = false, seq = 10)
        val a2Unread = article(id = 2, isRead = false, seq = 11)
        val page1 = """{"articles":[${enc(a1)}],"deleted_ids":[],"cursor":10,"has_more":true}"""
        val page2 = """{"articles":[${enc(a2Unread)}],"deleted_ids":[],"cursor":11,"has_more":false}"""

        lateinit var store: PersistentFakeArticleStore
        var syncCall = 0
        val api = makeApi { path ->
            when {
                path.endsWith("v1/sync") -> {
                    val body = if (syncCall == 0) {
                        // Between serving page 1 and page 2, the user marks
                        // article 2 read offline — enqueued mid-sync.
                        store.markRead(2, true)
                        store.enqueueMutation(2, true)
                        page1
                    } else {
                        page2
                    }
                    syncCall++
                    200 to body
                }
                else -> 404 to """{}"""
            }
        }
        store = PersistentFakeArticleStore()
        store.upsert(listOf(article(id = 2, isRead = false, seq = 0)))
        val engine = SyncEngine(api, store)

        engine.sync()

        assertTrue(
            store.backing.articles[2]!!.is_read,
            "a mutation enqueued mid-sync must be guarded on the later page",
        )
        assertEquals(
            mapOf(2 to true), store.backing.mutations,
            "the mid-sync mutation stays queued (never flushed)",
        )
    }

    // -----------------------------------------------------------------------
    // Flush: a since-deleted id is cleanly dequeued on the batch's success.
    // -----------------------------------------------------------------------

    /**
     * The batched flush endpoint (`POST /v1/articles/read`) is `UPDATE … WHERE id
     * IN (…)`: an id that no longer exists server-side is simply ignored and the
     * call returns 200. So a queued mutation for a since-deleted article is cleanly
     * dequeued on the batch's success — no per-id 404 handling needed, no immortal
     * queue entry.
     */
    @Test
    fun flush_deletedIdDequeuedOnSuccessfulBatch() = runTest {
        val api = makeApi { path ->
            when {
                // The batch POST: server ignores the missing id, returns updated:0.
                path.endsWith("articles/read") -> 200 to """{"data":{"updated":0}}"""
                // markAsRead's own per-id PUT: the article is gone server-side (404).
                path.contains("/read") -> 404 to """{}"""
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        store.upsert(listOf(article(id = 9, isRead = false)))
        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        // markAsRead's own PUT 404s but is swallowed (only Cancellation/401 rethrow),
        // so the entry stays queued; the batched flush is what clears it.
        repo.markAsRead(9)
        assertEquals(
            mapOf(9 to true), store.backing.mutations,
            "markAsRead's 404 leaves the entry queued",
        )

        engine.sync()

        assertTrue(
            store.backing.mutations.isEmpty(),
            "a since-deleted id is dequeued on the batch's 200 (no immortal entry)",
        )
    }

    /**
     * A retryable 4xx (401 — expired session, or 429 — rate limited) during flush
     * must leave the queue entry intact, unlike a permanent 404/410. Dropping here
     * would silently discard the user's offline change before they can retry or
     * re-authenticate — the exact data loss the queue exists to prevent.
     */
    @Test
    fun flush_keepsMutationQueuedOnRetryable4xx() = runTest {
        var readAttempts = 0
        val api = makeApi { path ->
            when {
                path.contains("/read") -> {
                    readAttempts++
                    401 to """{}"""  // expired session
                }
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        store.upsert(listOf(article(id = 9, isRead = false)))
        val engine = SyncEngine(api, store)

        // Seed the queue directly (markAsRead now rethrows a 401 — see
        // markAsRead_rethrows401ButKeepsMutationQueued); this test isolates the
        // flush path, which must keep a retryable 4xx queued.
        store.enqueueMutation(9, true)
        store.markRead(9, isRead = true)
        assertEquals(mapOf(9 to true), store.backing.mutations, "a queued 401-retryable mutation")

        engine.sync()

        assertTrue(readAttempts > 0, "the flush must have attempted the PUT")
        assertEquals(
            mapOf(9 to true), store.backing.mutations,
            "a retryable 4xx (401) during flush must NOT drop the queue entry",
        )
    }

    // -----------------------------------------------------------------------
    // markAsRead/Unread error surfacing: a 401 is rethrown so the session-expiry
    // modal still fires, while the mutation is preserved for the post-re-auth flush.
    // -----------------------------------------------------------------------

    /**
     * When the mark PUT returns 401 (expired session), markAsRead must rethrow the
     * [ClientRequestException] — FeedViewModel.onApiError turns that into the SESSION
     * EXPIRED modal (ERR-1) — while still updating the local mirror and leaving the
     * mutation queued so it flushes after re-authentication.
     */
    @Test
    fun markAsRead_rethrows401ButKeepsMutationQueued() = runTest {
        val api = makeApi { path ->
            when {
                path.contains("/read") -> 401 to """{}"""  // expired session
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        store.upsert(listOf(article(id = 3, isRead = false)))
        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        val thrown = assertFailsWith<ClientRequestException> { repo.markAsRead(3) }
        assertEquals(HttpStatusCode.Unauthorized, thrown.response.status, "the 401 must propagate")
        assertTrue(store.backing.articles[3]!!.is_read, "local mirror is still updated optimistically")
        assertEquals(
            mapOf(3 to true), store.backing.mutations,
            "the mutation stays queued for the post-re-auth flush",
        )
    }

    /**
     * A non-401 client error (e.g. 400) is NOT rethrown — the offline-first contract
     * swallows it and leaves the mutation queued for SyncEngine to retry/resolve.
     */
    @Test
    fun markAsRead_swallowsNon401ClientError() = runTest {
        val api = makeApi { path ->
            when {
                path.contains("/read") -> 400 to """{}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        store.upsert(listOf(article(id = 4, isRead = false)))
        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        repo.markAsRead(4)  // must NOT throw

        assertTrue(store.backing.articles[4]!!.is_read, "local mirror updated")
        assertEquals(
            mapOf(4 to true), store.backing.mutations,
            "a non-401 client error stays queued and does not throw",
        )
    }

    // -----------------------------------------------------------------------
    // full_resync + pending queue: clear() preserves the queue, and the pull
    // guard re-applies the queued read state to the re-backfilled article.
    // -----------------------------------------------------------------------

    /**
     * End-to-end through [SyncEngine.sync]: while a local read-state change is
     * un-acked (offline), the server responds `full_resync`. The engine clears the
     * store and re-backfills — the article comes back with the server's stale
     * `is_read` — but the queue survives `clear()` and the per-page pull guard
     * re-applies the queued read state, so the user's change is not lost even
     * though the article was fully wiped mid-sync. A later online sync flushes it.
     */
    @Test
    fun fullResync_withPendingMutation_preservesLocalStateThroughBackfill() = runTest {
        val a1Unread = article(id = 1, isRead = false, seq = 1)
        var syncCall = 0
        val syncResponses = listOf(
            articleDelta(a1Unread, cursor = 1L),  // 0: initial load, cursor -> 1
            """{"full_resync": true}""",           // 1: server demands a full re-backfill
            articleDelta(a1Unread, cursor = 2L),   // 2: backfill re-delivers article 1 (still unread)
        )
        val api = makeApi { path ->
            when {
                path.contains("/read") -> 500 to """{}"""  // flush always fails (offline)
                path.endsWith("v1/sync") -> {
                    val resp = syncResponses.getOrElse(syncCall) { emptyDelta(cursor = 2L) }
                    syncCall++
                    200 to resp
                }
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        val engine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, engine)

        // Initial sync loads article 1 (unread) and advances the cursor past 0 so a
        // later full_resync actually triggers clear() (a full_resync at cursor 0 is
        // treated as unrecoverable and skipped).
        engine.sync()
        assertFalse(store.backing.articles[1]!!.is_read, "article 1 loaded as unread")
        assertEquals(1L, store.backing.cursor, "cursor advanced past 0")

        // User marks article 1 read while offline (PUT 500 → swallowed, queued).
        repo.markAsRead(1)
        assertEquals(mapOf(1 to true), store.backing.mutations, "mutation queued offline")

        // Next sync: flush fails; server returns full_resync → clear() wipes article 1,
        // then the backfill re-delivers it as unread. The guard must re-apply the
        // queued read state despite the intervening clear().
        engine.sync()

        assertTrue(
            store.backing.articles[1]!!.is_read,
            "queued read state must survive full_resync clear() + backfill",
        )
        assertEquals(
            mapOf(1 to true), store.backing.mutations,
            "mutation stays queued because the flush kept failing",
        )
    }

    // -----------------------------------------------------------------------
    // flush: mutations are batched by desired read-state into at most two calls.
    // -----------------------------------------------------------------------

    /**
     * A queue containing both read and unread desired states must flush in exactly
     * two batched `POST /v1/articles/read` calls (one per group), and all entries
     * are dequeued on success — instead of one PUT per id.
     */
    @Test
    fun flush_batchesByReadStateIntoAtMostTwoCalls() = runTest {
        var readPosts = 0
        val api = makeApi { path ->
            when {
                path.endsWith("articles/read") -> {
                    readPosts++
                    200 to readUpdateAck
                }
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        store.enqueueMutation(1, true)
        store.enqueueMutation(2, true)
        store.enqueueMutation(3, false)
        val engine = SyncEngine(api, store)

        engine.sync()

        assertEquals(
            2, readPosts,
            "three mutations across two read-states must flush in exactly two batched calls",
        )
        assertTrue(
            store.backing.mutations.isEmpty(),
            "every entry must be dequeued after its group's batch is acked",
        )
    }

    /**
     * A whole-batch failure (5xx / offline) leaves the entire group queued for the
     * next sync — the batch is all-or-nothing at the HTTP level.
     */
    @Test
    fun flush_wholeGroupStaysQueuedOnFailure() = runTest {
        val api = makeApi { path ->
            when {
                path.endsWith("articles/read") -> 500 to """{}"""
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        store.enqueueMutation(1, true)
        store.enqueueMutation(2, true)
        val engine = SyncEngine(api, store)

        engine.sync()

        assertEquals(
            mapOf(1 to true, 2 to true), store.backing.mutations,
            "a whole-batch failure must leave the entire group queued for the next sync",
        )
    }

    // -----------------------------------------------------------------------
    // flush: a group larger than FeedApi.MAX_ARTICLE_IDS_PER_BATCH is chunked
    // into multiple bounded requests, so it stays within the server's SQL
    // host-parameter limit and doesn't wedge the queue permanently.
    // -----------------------------------------------------------------------

    /**
     * A single-state group larger than [FeedApi.MAX_ARTICLE_IDS_PER_BATCH] must be
     * split into multiple `POST /v1/articles/read` calls, each dequeued on its own
     * ack, instead of one unbounded request that would exceed the server's SQL
     * host-parameter limit.
     */
    @Test
    fun flush_chunksGroupLargerThanMaxBatchIntoMultipleRequests() = runTest {
        var readPosts = 0
        val api = makeApi { path ->
            when {
                path.endsWith("articles/read") -> {
                    readPosts++
                    200 to readUpdateAck
                }
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        // One more than the cap forces a second chunk.
        (1..(FeedApi.MAX_ARTICLE_IDS_PER_BATCH + 1)).forEach { store.enqueueMutation(it, true) }
        val engine = SyncEngine(api, store)

        engine.sync()

        assertEquals(
            2, readPosts,
            "a group of MAX_ARTICLE_IDS_PER_BATCH + 1 ids must flush in exactly two chunked requests",
        )
        assertTrue(store.backing.mutations.isEmpty(), "every chunk must be dequeued after its own ack")
    }

    /**
     * When a chunked group's first chunk succeeds and a later chunk fails, only
     * the failed chunk's ids stay queued — the whole group is no longer an
     * all-or-nothing unit once it's split, so partial progress survives a flaky
     * reconnect instead of re-sending already-acked ids forever.
     */
    @Test
    fun flush_partialChunkFailureLeavesOnlyThatChunkQueued() = runTest {
        var readPosts = 0
        val api = makeApi { path ->
            when {
                path.endsWith("articles/read") -> {
                    readPosts++
                    // First chunk acks, second (and any later) chunk fails.
                    if (readPosts == 1) 200 to readUpdateAck else 500 to """{}"""
                }
                path.endsWith("v1/sync") -> 200 to emptyDelta()
                path.endsWith("v1/feeds") -> 200 to """{"data":[]}"""
                else -> 404 to """{}"""
            }
        }
        val store = PersistentFakeArticleStore()
        (1..(FeedApi.MAX_ARTICLE_IDS_PER_BATCH + 1)).forEach { store.enqueueMutation(it, true) }
        val engine = SyncEngine(api, store)

        engine.sync()

        assertEquals(2, readPosts, "both chunks must be attempted")
        assertEquals(
            mapOf((FeedApi.MAX_ARTICLE_IDS_PER_BATCH + 1) to true), store.backing.mutations,
            "only the failed second chunk's id should remain queued, not the whole group",
        )
    }
}
