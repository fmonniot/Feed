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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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

        override suspend fun cursor(): Long = _cursor

        override suspend fun setCursor(seq: Long) { _cursor = seq }

        override suspend fun clear() {
            _articles.value = emptyMap()
            _cursor = 0
        }

        private fun matchesFilter(article: Article, filter: ArticleFilter): Boolean = when (filter) {
            is ArticleFilter.All -> true
            is ArticleFilter.UnreadOnly -> !article.is_read
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
}
