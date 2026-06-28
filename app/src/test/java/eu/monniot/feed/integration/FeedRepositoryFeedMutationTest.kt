package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.SharedFeedRepository
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.LoginRequest
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.SyncEngine
import eu.monniot.feed.store.RoomArticleStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Feed update/delete/refresh coverage. Split out from FeedRepositoryFeedsTest
// so the two halves run in separate forks (a single class can't span forks,
// so the combined class pinned the CI critical path).
@RunWith(RobolectricTestRunner::class)
class FeedRepositoryFeedMutationTest {
    @get:Rule
    val server = ServerRule()

    private val rss = MockRssServer()

    private lateinit var db: FeedDatabase
    private lateinit var client: HttpClient
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
        val feedApi = FeedApi(client)
        val store = RoomArticleStore(db, db.articleStoreDao())
        repository = SharedFeedRepository(feedApi, store, SyncEngine(feedApi, store))
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
        rss.shutdown()
    }

    @Test
    fun `updateFeed null customTitle clears it`() = runTest {
        rss.enqueueRssFeed("Original Title")
        val added = repository.addFeed(rss.baseUrl)
        repository.updateFeed(added.id, "Custom Name", 30, false)
        repository.updateFeed(added.id, null, 30, false)
        val feeds = repository.getFeeds()
        assertNull(feeds[0].custom_title)
    }

    @Test(expected = ClientRequestException::class)
    fun `updateFeed interval below 5 throws ClientRequestException 400`() = runTest {
        rss.enqueueRssFeed()
        val added = repository.addFeed(rss.baseUrl)
        repository.updateFeed(added.id, null, 4, false)
    }

    @Test
    fun `updateFeed sets isPaused true`() = runTest {
        rss.enqueueRssFeed()
        val added = repository.addFeed(rss.baseUrl)
        repository.updateFeed(added.id, null, 30, true)
        val feeds = repository.getFeeds()
        assertTrue(feeds[0].is_paused)
    }

    @Test
    fun `updateFeed sets isPaused false`() = runTest {
        rss.enqueueRssFeed()
        val added = repository.addFeed(rss.baseUrl)
        repository.updateFeed(added.id, null, 30, true)
        repository.updateFeed(added.id, null, 30, false)
        val feeds = repository.getFeeds()
        assertTrue(!feeds[0].is_paused)
    }

    @Test
    fun `deleteFeed removes feed from list`() = runTest {
        rss.enqueueRssFeed()
        val added = repository.addFeed(rss.baseUrl)
        repository.deleteFeed(added.id)
        val feeds = repository.getFeeds()
        assertTrue(feeds.isEmpty())
    }

    @Test
    fun `refresh stores feed title in articles`() = runTest {
        rss.enqueueRssFeedWithItems(title = "My Source Feed", itemCount = 1)
        repository.addFeed(rss.baseUrl)
        repository.refresh()
        val items = repository.observePage(ArticleFilter.All, 0..49).first()
        assertTrue("Expected at least one article after refresh", items.isNotEmpty())
        assertEquals("My Source Feed", items[0].feedTitle)
    }
}
