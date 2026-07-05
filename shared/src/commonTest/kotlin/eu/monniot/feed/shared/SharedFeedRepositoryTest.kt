package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.SyncResponse
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import eu.monniot.feed.shared.sync.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T12 — `observeUnreadCount` equals the unread rows visible through the same
 * filter as the windowed list, for all-tab and per-feed (badge == list by
 * construction); list reads come from `observePage`, never a whole-corpus load.
 *
 * Tests [SharedFeedRepository] with an in-memory [InMemoryArticleStore].
 */
class SharedFeedRepositoryTest {

    /**
     * In-memory [ArticleStore] that implements windowed reads and unread counts.
     * Uses a [MutableStateFlow] of the article map so observers are notified
     * reactively on mutations.
     */
    private class InMemoryArticleStore : ArticleStore {
        private val _articles = MutableStateFlow<Map<Int, Article>>(emptyMap())
        private var _cursor = 0L

        override suspend fun upsert(articles: List<Article>) {
            _articles.update { current ->
                current.toMutableMap().apply {
                    for (a in articles) put(a.id, a)
                }
            }
        }

        override suspend fun deleteByIds(ids: List<Long>) {
            _articles.update { current ->
                current.toMutableMap().apply {
                    for (id in ids) remove(id.toInt())
                }
            }
        }

        override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> =
            _articles.map { articlesMap ->
                articlesMap.values
                    .filter { matchesFilter(it, filter) }
                    .sortedWith(compareByDescending<Article> { it.published ?: Long.MIN_VALUE }
                        .thenByDescending { it.seq })
                    .let { sorted ->
                        val start = window.first.coerceAtMost(sorted.size)
                        val end = (window.last + 1).coerceAtMost(sorted.size)
                        sorted.subList(start, end)
                    }
            }

        override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
            _articles.map { articlesMap ->
                articlesMap.values.count { !it.is_read && matchesFilter(it, filter) }
            }

        override fun observeTotalCount(): Flow<Int> =
            _articles.map { articlesMap -> articlesMap.size }

        override fun observeCount(filter: ArticleFilter): Flow<Int> =
            _articles.map { articlesMap ->
                when (filter) {
                    is ArticleFilter.All -> articlesMap.size
                    is ArticleFilter.UnreadOnly -> articlesMap.values.count { !it.is_read }
                    is ArticleFilter.ByFeed -> articlesMap.values.count { it.feed_id == filter.feedId }
                }
            }

        override suspend fun cursor(): Long = _cursor

        override suspend fun setCursor(seq: Long) { _cursor = seq }

        override suspend fun markRead(id: Int, isRead: Boolean) {
            _articles.update { current ->
                val article = current[id] ?: return
                current + (id to article.copy(is_read = isRead))
            }
        }

        override suspend fun deleteByFeedId(feedId: Int) {
            _articles.update { current ->
                current.filterValues { it.feed_id != feedId }
            }
        }

        override suspend fun clear() {
            _articles.value = emptyMap()
            _cursor = 0
        }

        // Offline mutation queue — in-memory stub for T12 tests.
        private val _mutations = mutableMapOf<Int, Boolean>()
        /** Optional hook run at the start of dequeueMutation (e.g. to suspend so a
         *  cancellation test can cancel inside the markAsRead try block). */
        var dequeueHook: (suspend () -> Unit)? = null
        override suspend fun enqueueMutation(id: Int, isRead: Boolean) { _mutations[id] = isRead }
        override suspend fun dequeueMutation(id: Int, isRead: Boolean) {
            dequeueHook?.invoke()
            if (_mutations[id] == isRead) _mutations.remove(id)
        }
        override suspend fun pendingMutations(): Map<Int, Boolean> = _mutations.toMap()

        private fun matchesFilter(article: Article, filter: ArticleFilter): Boolean = when (filter) {
            is ArticleFilter.All -> true
            is ArticleFilter.UnreadOnly -> !article.is_read || article.id == filter.keepArticleId
            is ArticleFilter.ByFeed -> article.feed_id == filter.feedId
        }
    }

    private fun makeArticle(
        id: Int,
        feedId: Int,
        isRead: Boolean = false,
        published: Long? = null,
        seq: Long = id.toLong(),
    ) = Article(
        id = id,
        feed_id = feedId,
        guid = "guid-$id",
        title = "Article $id",
        content = "Content for article $id",
        link = "https://example.com/$id",
        author = null,
        published = published,
        is_read = isRead,
        fetched_at = null,
        seq = seq,
    )

    private fun makeFeed(id: Int, title: String) = Feed(
        id = id,
        url = "https://example.com/feed/$id",
        title = title,
        custom_title = null,
        is_paused = false,
        fetch_interval_minutes = 60,
        error_count = 0,
        last_fetched = null,
        unread_count = null,
        category_id = null,
    )

    private fun makeRepo(store: InMemoryArticleStore): SharedFeedRepository {
        val api = FeedApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
        val syncEngine = SyncEngine(api, store)
        return SharedFeedRepository(api, store, syncEngine)
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun makeJsonApi(responseBody: String): FeedApi {
        val engine = MockEngine {
            respond(responseBody, HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FeedApi(client)
    }

    private fun makeRepoWithJsonApi(store: InMemoryArticleStore, responseBody: String): SharedFeedRepository {
        val api = makeJsonApi(responseBody)
        val syncEngine = SyncEngine(api, store)
        return SharedFeedRepository(api, store, syncEngine)
    }

    /**
     * A [FeedApi] backed by a path-routing [MockEngine]. Needed for
     * [markAllAsRead]/[markFeedAsRead] tests: both call `refresh()` afterward,
     * which in turn hits `GET /v1/sync` and `GET /v1/feeds` — a single fixed
     * response body (as in [makeJsonApi]) can't satisfy all three request
     * shapes at once.
     *
     * @param routes map from a substring of the request path to the JSON body
     *               returned when the request URL contains it (checked in
     *               iteration order — put more specific paths first).
     * @param recordedRequests optional list every matched request path is
     *                         appended to, so tests can assert call order/count.
     */
    private fun makeRoutingJsonApi(
        routes: List<Pair<String, String>>,
        recordedRequests: MutableList<String> = mutableListOf(),
    ): FeedApi {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            recordedRequests += path
            val body = routes.firstOrNull { (segment, _) -> segment in path }?.second
                ?: error("no mock route configured for $path")
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return FeedApi(client)
    }

    private val emptySyncResponse = """{"articles":[],"deleted_ids":[],"cursor":0,"has_more":false}"""
    private val emptyFeedsResponse = """{"data":[]}"""

    // ── T12: badge == list by construction ──────────────────────────────────

    @Test
    fun unreadCountEqualsUnreadRowsInList_allTab() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepo(store)

        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = true, published = 200),
            makeArticle(3, feedId = 2, isRead = false, published = 300),
            makeArticle(4, feedId = 2, isRead = false, published = 400),
            makeArticle(5, feedId = 1, isRead = true, published = 500),
        ))

        val filter = ArticleFilter.All
        val page = repo.observePage(filter, 0..49).first()
        val badge = repo.observeUnreadCount(filter).first()

        val unreadInList = page.count { !it.isRead }
        assertEquals(unreadInList, badge, "badge must equal the unread count visible in the list (all-tab)")
        assertEquals(3, badge, "3 of 5 articles are unread")
    }

    @Test
    fun unreadCountEqualsUnreadRowsInList_perFeed() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepo(store)

        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = true, published = 200),
            makeArticle(3, feedId = 2, isRead = false, published = 300),
            makeArticle(4, feedId = 2, isRead = false, published = 400),
            makeArticle(5, feedId = 1, isRead = false, published = 500),
        ))

        val filter1 = ArticleFilter.ByFeed(1)
        val page1 = repo.observePage(filter1, 0..49).first()
        val badge1 = repo.observeUnreadCount(filter1).first()

        val unread1 = page1.count { !it.isRead }
        assertEquals(unread1, badge1, "badge must equal unread count in list for feed 1")
        assertEquals(2, badge1, "feed 1 has 2 unread articles")
        assertEquals(3, page1.size, "feed 1 has 3 total articles")

        val filter2 = ArticleFilter.ByFeed(2)
        val page2 = repo.observePage(filter2, 0..49).first()
        val badge2 = repo.observeUnreadCount(filter2).first()

        val unread2 = page2.count { !it.isRead }
        assertEquals(unread2, badge2, "badge must equal unread count in list for feed 2")
        assertEquals(2, badge2, "feed 2 has 2 unread articles")
        assertEquals(2, page2.size, "feed 2 has 2 total articles")
    }

    @Test
    fun listReadsAreWindowed_neverWholeCopus() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepo(store)

        val articles = (1..100).map { i ->
            makeArticle(i, feedId = 1, published = i.toLong(), seq = i.toLong())
        }
        store.upsert(articles)

        val window = 0..9
        val page = repo.observePage(ArticleFilter.All, window).first()

        assertEquals(10, page.size, "windowed query must return only the requested window size")
        assertEquals("100", page.first().id, "first article should be the most recently published (DESC order)")
    }

    @Test
    fun observePageMapsArticleToArticleItem() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepo(store)

        store.upsert(listOf(
            makeArticle(42, feedId = 7, isRead = true, published = 1000000),
        ))

        val items = repo.observePage(ArticleFilter.All, 0..49).first()
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("42", item.id, "id must be stringified")
        assertEquals("Article 42", item.title)
        assertEquals(7, item.feedId)
        assertTrue(item.isRead, "isRead must be mapped")
    }

    @Test
    fun badgeAndListStayConsistentAfterMutation() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepo(store)

        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = false, published = 200),
        ))

        val filter = ArticleFilter.All
        assertEquals(2, repo.observeUnreadCount(filter).first())
        assertEquals(2, repo.observePage(filter, 0..49).first().count { !it.isRead })

        // Mark one as read via upsert (simulating a sync delta)
        store.upsert(listOf(makeArticle(1, feedId = 1, isRead = true, published = 100)))

        assertEquals(1, repo.observeUnreadCount(filter).first(), "badge must reflect the mutation")
        assertEquals(1, repo.observePage(filter, 0..49).first().count { !it.isRead },
            "list unread count must match badge after mutation")
    }

    @Test
    fun badgeAndListConsistentAfterDeletion() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepo(store)

        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = false, published = 200),
            makeArticle(3, feedId = 2, isRead = false, published = 300),
        ))

        val filterAll = ArticleFilter.All
        assertEquals(3, repo.observeUnreadCount(filterAll).first())

        store.deleteByIds(listOf(2L))

        assertEquals(2, repo.observeUnreadCount(filterAll).first(),
            "badge must decrease after deletion")
        assertEquals(2, repo.observePage(filterAll, 0..49).first().size,
            "list size must match after deletion")

        val filterFeed1 = ArticleFilter.ByFeed(1)
        assertEquals(1, repo.observeUnreadCount(filterFeed1).first(),
            "per-feed badge consistent after cross-feed deletion")
        assertEquals(1, repo.observePage(filterFeed1, 0..49).first().size)
    }

    // ── markAsRead / markAsUnread update local store ───────────────────────

    @Test
    fun markAsReadUpdatesStoreAndBadge() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepoWithJsonApi(store, """{"data":{"updated":1}}""")

        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = false, published = 200),
        ))

        assertEquals(2, repo.observeUnreadCount(ArticleFilter.All).first())

        repo.markAsRead(1)

        assertEquals(1, repo.observeUnreadCount(ArticleFilter.All).first(),
            "badge must decrease after markAsRead")
        val page = repo.observePage(ArticleFilter.All, 0..49).first()
        assertTrue(page.first { it.id == "1" }.isRead, "article 1 must be marked as read in the list")
    }

    @Test
    fun markAsUnreadUpdatesStoreAndBadge() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepoWithJsonApi(store, """{"data":{"updated":1}}""")

        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = true, published = 100),
        ))

        assertEquals(0, repo.observeUnreadCount(ArticleFilter.All).first())

        repo.markAsUnread(1)

        assertEquals(1, repo.observeUnreadCount(ArticleFilter.All).first(),
            "badge must increase after markAsUnread")
    }

    @Test
    fun markAsRead_rethrowsCancellation_andKeepsMutationQueued() = runTest {
        val store = InMemoryArticleStore()
        store.upsert(listOf(makeArticle(1, feedId = 1, isRead = false)))

        // The PUT succeeds, then dequeueMutation suspends forever; withTimeout
        // cancels the coroutine while it's inside the markAsRead try block,
        // producing a genuine CancellationException. If markAsRead's catch
        // swallowed it (the reviewer's bug), the block would complete normally
        // and assertFailsWith would fail. (Ktor's MockEngine masks a
        // cancellation thrown from the engine itself, so the suspension point is
        // placed in the test store, which is the same try block.)
        store.dequeueHook = { awaitCancellation() }
        val repo = makeRepoWithJsonApi(store, """{"data":{"updated":1}}""")

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1_000) { repo.markAsRead(1) }
        }
        assertEquals(
            true, store.pendingMutations()[1],
            "the optimistic mutation must stay queued so a later flush can retry",
        )
    }

    // ── batch read operations (ticket #9) ──────────────────────────────────

    @Test
    fun markAllAsRead_callsReadAllEndpointThenRefreshesMirror() = runTest {
        val store = InMemoryArticleStore()
        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = false, published = 200),
        ))
        val requests = mutableListOf<String>()
        val api = makeRoutingJsonApi(
            routes = listOf(
                "articles/read-all" to """{"data":{"updated":2}}""",
                // The refresh() that follows re-syncs and echoes both articles as read.
                "v1/sync" to """{"articles":[
                    {"id":1,"feed_id":1,"guid":"g1","title":"A1","content":null,"link":null,"author":null,"published":100,"is_read":true,"fetched_at":null,"seq":10},
                    {"id":2,"feed_id":1,"guid":"g2","title":"A2","content":null,"link":null,"author":null,"published":200,"is_read":true,"fetched_at":null,"seq":11}
                ],"deleted_ids":[],"cursor":11,"has_more":false}""",
                "v1/feeds" to emptyFeedsResponse,
            ),
            recordedRequests = requests,
        )
        val syncEngine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, syncEngine)

        repo.markAllAsRead()

        assertTrue(requests.any { "articles/read-all" in it }, "must call POST /v1/articles/read-all")
        assertTrue(requests.any { "v1/sync" in it }, "must call GET /v1/sync (refresh) after the bulk endpoint")
        assertEquals(0, repo.observeUnreadCount(ArticleFilter.All).first(),
            "mirror must reflect the server's is_read flips after refresh")
    }

    @Test
    fun markFeedAsRead_callsFeedReadEndpointThenRefreshesMirror() = runTest {
        val store = InMemoryArticleStore()
        store.upsert(listOf(
            makeArticle(1, feedId = 7, isRead = false, published = 100),
        ))
        val requests = mutableListOf<String>()
        val api = makeRoutingJsonApi(
            routes = listOf(
                "feeds/7/read" to """{"data":{"updated":1}}""",
                "v1/sync" to """{"articles":[
                    {"id":1,"feed_id":7,"guid":"g1","title":"A1","content":null,"link":null,"author":null,"published":100,"is_read":true,"fetched_at":null,"seq":10}
                ],"deleted_ids":[],"cursor":10,"has_more":false}""",
                "v1/feeds" to emptyFeedsResponse,
            ),
            recordedRequests = requests,
        )
        val syncEngine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, syncEngine)

        repo.markFeedAsRead(7)

        assertTrue(requests.any { "feeds/7/read" in it }, "must call POST /v1/feeds/7/read")
        assertTrue(requests.any { "v1/sync" in it }, "must call GET /v1/sync (refresh) after the bulk endpoint")
        assertEquals(0, repo.observeUnreadCount(ArticleFilter.All).first(),
            "mirror must reflect the server's is_read flip after refresh")
    }

    @Test
    fun markAllAsRead_rethrows401SoSessionExpiryModalFires() = runTest {
        val store = InMemoryArticleStore()
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val client = HttpClient(engine) { expectSuccess = true }
        val api = FeedApi(client)
        val syncEngine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, syncEngine)

        assertFailsWith<ClientRequestException> { repo.markAllAsRead() }
    }

    @Test
    fun markFeedAsRead_rethrows401SoSessionExpiryModalFires() = runTest {
        val store = InMemoryArticleStore()
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val client = HttpClient(engine) { expectSuccess = true }
        val api = FeedApi(client)
        val syncEngine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, syncEngine)

        assertFailsWith<ClientRequestException> { repo.markFeedAsRead(1) }
    }

    @Test
    fun markArticlesAsRead_optimisticallyMarksEachIdAndCallsBatchEndpoint() = runTest {
        val store = InMemoryArticleStore()
        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = false, published = 200),
            makeArticle(3, feedId = 1, isRead = false, published = 300),
        ))
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.encodedPath
            respond("""{"data":{"updated":2}}""", HttpStatusCode.OK, jsonHeaders)
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val api = FeedApi(client)
        val syncEngine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, syncEngine)

        repo.markArticlesAsRead(listOf(1, 2))

        assertEquals(1, requests.size, "must issue a single batched call, not one per id")
        assertTrue("articles/read" in requests.first())
        assertEquals(0, store.pendingMutations().size, "mutations must be dequeued after a successful batch ack")
        val page = repo.observePage(ArticleFilter.All, 0..49).first()
        assertTrue(page.first { it.id == "1" }.isRead, "article 1 must be marked read locally")
        assertTrue(page.first { it.id == "2" }.isRead, "article 2 must be marked read locally")
        assertTrue(!page.first { it.id == "3" }.isRead, "article 3 (not in the batch) must stay unread")
    }

    @Test
    fun markArticlesAsRead_rethrows401AndKeepsMutationsQueued() = runTest {
        val store = InMemoryArticleStore()
        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false),
            makeArticle(2, feedId = 1, isRead = false),
        ))
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val api = FeedApi(client)
        val syncEngine = SyncEngine(api, store)
        val repo = SharedFeedRepository(api, store, syncEngine)

        assertFailsWith<ClientRequestException> { repo.markArticlesAsRead(listOf(1, 2)) }

        assertEquals(true, store.pendingMutations()[1], "mutation for id 1 must stay queued after a 401")
        assertEquals(true, store.pendingMutations()[2], "mutation for id 2 must stay queued after a 401")
        // Local mirror is still optimistically updated even though the server call failed.
        val page = repo.observePage(ArticleFilter.All, 0..49).first()
        assertTrue(page.all { it.isRead }, "local mirror stays optimistically marked read despite the 401")
    }

    // ── deleteFeed purges articles from store ──────────────────────────────

    @Test
    fun deleteFeedRemovesArticlesFromStore() = runTest {
        val store = InMemoryArticleStore()
        val repo = makeRepoWithJsonApi(store, "")

        store.upsert(listOf(
            makeArticle(1, feedId = 1, isRead = false, published = 100),
            makeArticle(2, feedId = 1, isRead = false, published = 200),
            makeArticle(3, feedId = 2, isRead = false, published = 300),
        ))

        assertEquals(3, repo.observeUnreadCount(ArticleFilter.All).first())

        repo.deleteFeed(1)

        assertEquals(1, repo.observeUnreadCount(ArticleFilter.All).first(),
            "badge must exclude articles from deleted feed")
        val page = repo.observePage(ArticleFilter.All, 0..49).first()
        assertEquals(1, page.size, "only feed 2's article should remain")
        assertEquals("3", page[0].id)
    }

    // ── feedTitle mapping with populated feeds cache ───────────────────────

    @Test
    fun observePageResolvesFeedTitle() = runTest {
        val store = InMemoryArticleStore()
        val feedsJson = """{"data":[
            {"id":1,"url":"https://example.com/feed/1","title":"Tech Blog","custom_title":null,"is_paused":false,"fetch_interval_minutes":60,"error_count":0,"last_fetched":null,"unread_count":0,"category_id":null},
            {"id":2,"url":"https://example.com/feed/2","title":"News","custom_title":"My News","is_paused":false,"fetch_interval_minutes":60,"error_count":0,"last_fetched":null,"unread_count":0,"category_id":null}
        ]}"""
        val repo = makeRepoWithJsonApi(store, feedsJson)

        store.upsert(listOf(
            makeArticle(1, feedId = 1, published = 100),
            makeArticle(2, feedId = 2, published = 200),
        ))

        // Seed the feeds cache
        repo.getFeeds()

        val page = repo.observePage(ArticleFilter.All, 0..49).first()
        assertEquals(2, page.size)
        assertEquals("Tech Blog", page.first { it.id == "1" }.feedTitle,
            "feedTitle falls back to feed.title when custom_title is null")
        assertEquals("My News", page.first { it.id == "2" }.feedTitle,
            "feedTitle prefers custom_title when set")
    }
}
