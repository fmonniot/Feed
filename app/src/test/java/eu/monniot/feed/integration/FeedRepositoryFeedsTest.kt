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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedRepositoryFeedsTest {
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
    fun `getFeeds returns empty list initially`() = runTest {
        val feeds = repository.getFeeds()
        assertTrue(feeds.isEmpty())
    }

    @Test(expected = ClientRequestException::class)
    fun `addFeed with non-http URL throws ClientRequestException 400`() = runTest {
        repository.addFeed("not-a-url")
    }

    @Test(expected = ClientRequestException::class)
    fun `addFeed with unreachable URL throws ClientRequestException 400`() = runTest {
        repository.addFeed("http://127.0.0.1:1/unreachable.xml")
    }

    @Test
    fun `addFeed with valid RSS returns positive feed id`() = runTest {
        rss.enqueueRssFeed("My Test Feed")
        val response = repository.addFeed(rss.baseUrl)
        assertTrue("Expected positive feed id, got ${response.id}", response.id > 0)
    }

    @Test
    fun `getFeeds returns feed after successful add`() = runTest {
        rss.enqueueRssFeed("My Test Feed")
        repository.addFeed(rss.baseUrl)
        val feeds = repository.getFeeds()
        assertEquals(1, feeds.size)
        assertEquals(rss.baseUrl, feeds[0].url)
    }

    @Test
    fun `updateFeed renames with custom title`() = runTest {
        rss.enqueueRssFeed("Original Title")
        val added = repository.addFeed(rss.baseUrl)
        repository.updateFeed(added.id, "Custom Name", 30, false)
        val feeds = repository.getFeeds()
        assertEquals("Custom Name", feeds[0].custom_title)
    }
}
