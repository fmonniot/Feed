package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.LoginRequest
import eu.monniot.feed.shared.api.SyncResponse
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BUG-22: Article count mismatch between subscriptions badge and article list.
 *
 * Validates that [FeedRepository.refreshForFeed] returns all articles for a
 * specific feed (up to the server's default page size of 50), rather than
 * relying on the global [FeedRepository.refresh] which fetches a cross-feed
 * top-50 and filters client-side.
 */
@RunWith(RobolectricTestRunner::class)
class FeedRepositoryArticleCountTest {
    @get:Rule
    val server = ServerRule()

    private val rss = MockRssServer()

    private lateinit var db: FeedDatabase
    private lateinit var client: HttpClient
    private lateinit var feedApi: FeedApi
    private lateinit var repository: FeedRepository

    @Before
    fun setUp() = runTest {
        rss.start()

        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        client = HttpClient(CIO) {
            expectSuccess = true
            install(HttpCookies) { storage = AcceptAllCookiesStorage() }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(DefaultRequest) { url(server.baseUrl) }
        }
        AuthApi(client).login(LoginRequest("admin", "admin"))
        feedApi = FeedApi(client)
        repository = FeedRepository(feedApi, db.rssItemDao())
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
        rss.shutdown()
    }

    @Test
    fun `refreshForFeed returns all articles for a specific feed`() = runTest {
        // Seed a feed with 5 articles
        rss.enqueueRssFeedWithItems("Feed A", itemCount = 5)
        val feedResponse = repository.addFeed(rss.baseUrl)
        val feedId = feedResponse.id

        // refreshForFeed should load articles for this feed into Room
        repository.refreshForFeed(feedId)
        val items = repository.items.first()

        assertEquals(
            "refreshForFeed must return all articles from the specific feed",
            5, items.size
        )
    }

    @Test
    fun `sync returns correct articles for specific feed`() = runTest {
        // Seed a feed with 5 articles
        rss.enqueueRssFeedWithItems("Feed B", itemCount = 5)
        val feedResponse = repository.addFeed(rss.baseUrl)
        val feedId = feedResponse.id

        // Verify sync endpoint returns the articles
        val response = feedApi.sync(since = 0)
        assertTrue(response is SyncResponse.Delta)
        val delta = response as SyncResponse.Delta
        val feedArticles = delta.articles.filter { it.feed_id == feedId }
        assertEquals(
            "Sync must return all articles for the feed",
            5, feedArticles.size
        )

        // All filtered articles must belong to the queried feed
        assertTrue(
            "All returned articles must belong to feedId=$feedId",
            feedArticles.all { it.feed_id == feedId }
        )
    }

    @Test
    fun `refreshForFeed loads correct article count`() = runTest {
        // Seed a feed with articles
        rss.enqueueRssFeedWithItems("Badge Test Feed", itemCount = 5)
        val feedResponse = repository.addFeed(rss.baseUrl)
        val feedId = feedResponse.id

        // Load articles via refreshForFeed
        repository.refreshForFeed(feedId)
        val articleItems = repository.items.first()

        assertEquals(
            "refreshForFeed must load all articles for the feed",
            5, articleItems.size
        )
    }

    @Test
    fun `sync paginates correctly for large feeds`() = runTest {
        rss.enqueueRssFeedWithItems("Paged Feed", itemCount = 50)
        val feedId = repository.addFeed(rss.baseUrl).id

        // Fetch via sync with a small page size to verify pagination
        val all = mutableListOf<eu.monniot.feed.shared.api.Article>()
        var since = 0L
        while (true) {
            val response = feedApi.sync(since = since, limit = 20)
            assertTrue(response is SyncResponse.Delta)
            val delta = response as SyncResponse.Delta
            all.addAll(delta.articles)
            if (!delta.hasMore) break
            since = delta.cursor
        }
        val feedArticles = all.filter { it.feed_id == feedId }
        assertEquals(50, feedArticles.size)
        assertTrue(feedArticles.all { it.feed_id == feedId })
    }

    @Test
    fun `refreshForFeed returns all unread when a feed has more than 50`() = runTest {
        rss.enqueueRssFeedWithItems("Big Feed", itemCount = 73)
        val feedId = repository.addFeed(rss.baseUrl).id

        repository.refreshForFeed(feedId)
        val items = repository.items.first()

        assertEquals("must return all articles for a >50-article feed", 73, items.size)
        assertTrue("must exceed the old 50 cap", items.size > 50)
    }

    @Test
    fun `refreshForFeed isolates articles to the selected feed`() = runTest {
        // Feed A: 3 articles
        rss.enqueueRssFeedWithItems("Feed A", itemCount = 3, guidPrefix = "feedA")
        val feedA = repository.addFeed(rss.urlForPath("/feedA.xml"))
        // Feed B: 5 articles
        rss.enqueueRssFeedWithItems("Feed B", itemCount = 5, guidPrefix = "feedB")
        val feedB = repository.addFeed(rss.urlForPath("/feedB.xml"))

        // Global refresh loads articles from both feeds into Room
        repository.refresh()
        val allItems = repository.items.first()
        assertEquals(
            "global refresh must return articles from both feeds",
            8, allItems.size
        )

        // refreshForFeed(A) must replace Room contents with only feed A's articles
        repository.refreshForFeed(feedA.id)
        val feedAItems = repository.items.first()
        assertEquals(
            "refreshForFeed must isolate to the selected feed's articles",
            3, feedAItems.size
        )
    }
}
