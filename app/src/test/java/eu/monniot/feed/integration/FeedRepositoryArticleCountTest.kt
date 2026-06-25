package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.LoginRequest
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
    fun `getFeedArticles API returns correct articles for specific feed`() = runTest {
        // Seed a feed with 5 articles
        rss.enqueueRssFeedWithItems("Feed B", itemCount = 5)
        val feedResponse = repository.addFeed(rss.baseUrl)
        val feedId = feedResponse.id

        // Direct API call to verify the endpoint works
        val response = feedApi.getFeedArticles(feedId)
        assertEquals(
            "GET /v1/feeds/{feedId}/articles must return all articles for the feed",
            5, response.data.size
        )

        // All articles must belong to the queried feed
        assertTrue(
            "All returned articles must belong to feedId=$feedId",
            response.data.all { it.feed_id == feedId }
        )
    }

    @Test
    fun `subscriptions badge matches article list count after refreshForFeed`() = runTest {
        // Seed a feed with articles
        rss.enqueueRssFeedWithItems("Badge Test Feed", itemCount = 5)
        val feedResponse = repository.addFeed(rss.baseUrl)
        val feedId = feedResponse.id

        // Get the feed's unread count (subscriptions badge)
        val feeds = repository.getFeeds()
        val feed = feeds.find { it.id == feedId }!!
        val badgeCount = feed.unread_count ?: 0

        // Load articles via refreshForFeed (the BUG-22 fix path)
        repository.refreshForFeed(feedId)
        val articleItems = repository.items.first()

        assertEquals(
            "Subscriptions badge count must match article list count after refreshForFeed",
            badgeCount, articleItems.size
        )
    }

    @Test
    fun `getAllFeedArticles follows pages past one page`() = runTest {
        rss.enqueueRssFeedWithItems("Paged Feed", itemCount = 50)
        val feedId = repository.addFeed(rss.baseUrl).id

        val all = feedApi.getAllFeedArticles(feedId, pageSize = 20)
        assertEquals(50, all.size)
        assertTrue(all.all { it.feed_id == feedId })
    }

    @Test
    fun `refreshForFeed returns all unread when a feed has more than 50`() = runTest {
        rss.enqueueRssFeedWithItems("Big Feed", itemCount = 73)
        val feedId = repository.addFeed(rss.baseUrl).id

        val badge = repository.getFeeds().first { it.id == feedId }.unread_count ?: 0
        repository.refreshForFeed(feedId)
        val items = repository.items.first()

        assertEquals("badge must equal list count for a >50-unread feed", badge, items.size)
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
