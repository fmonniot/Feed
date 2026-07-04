package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.SharedFeedRepository
import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.FeedApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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

        override suspend fun dequeueMutation(id: Int) {
            backing.mutations.remove(id)
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
        val json = Json { ignoreUnknownKeys = true }
        val articleJson = json.encodeToString(Article.serializer(), article)
        return """{"articles":[$articleJson],"deleted_ids":[],"cursor":$cursor,"has_more":false}"""
    }

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

        // The PUT was fired during flush.
        assertTrue(putCalls.isNotEmpty(), "PUT must be issued during sync on reconnect")
        assertEquals("1", putCalls[0].first, "PUT was issued for article id=1")
        // Queue is cleared after successful ack.
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
        val putCalls = mutableListOf<Int>()
        val onlineApi = makeApi { path ->
            when {
                path.contains("/read") -> {
                    val id = path.split("/").dropLast(1).last().toIntOrNull() ?: 0
                    putCalls.add(id)
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

        // The mutation was flushed.
        assertTrue(putCalls.contains(7), "PUT for article 7 must be fired after process death recovery")
        assertTrue(backing.mutations.isEmpty(), "mutation dequeued after successful ack")
    }
}
