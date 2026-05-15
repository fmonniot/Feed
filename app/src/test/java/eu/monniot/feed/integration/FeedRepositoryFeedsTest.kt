package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.NetworkModule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException

@RunWith(RobolectricTestRunner::class)
class FeedRepositoryFeedsTest {
    @get:Rule
    val server = ServerRule()

    private val rss = MockRssServer()

    private lateinit var db: FeedDatabase
    private lateinit var repository: FeedRepository

    @Before
    fun setUp() = runTest {
        rss.start()

        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val tokenManager = InMemoryTokenManager()
        val authApi = NetworkModule.createAuthApi()
        val feedApi = NetworkModule.createFeedV1Api(tokenManager, authApi)

        val loginResponse = authApi.login(LoginRequest("admin", "admin"))
        tokenManager.saveTokens(loginResponse.access_token, loginResponse.refresh_token)

        repository = FeedRepository(feedApi, db.rssItemDao())
    }

    @After
    fun tearDown() {
        db.close()
        rss.shutdown()
    }

    @Test
    fun `getFeeds returns empty list initially`() = runTest {
        val feeds = repository.getFeeds()
        assertTrue(feeds.isEmpty())
    }

    @Test(expected = HttpException::class)
    fun `addFeed with non-http URL throws HttpException 400`() = runTest {
        repository.addFeed("not-a-url")
    }

    @Test(expected = HttpException::class)
    fun `addFeed with unreachable URL throws HttpException 400`() = runTest {
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

    @Test
    fun `updateFeed null customTitle clears it`() = runTest {
        rss.enqueueRssFeed("Original Title")
        val added = repository.addFeed(rss.baseUrl)
        repository.updateFeed(added.id, "Custom Name", 30, false)
        repository.updateFeed(added.id, null, 30, false)
        val feeds = repository.getFeeds()
        assertNull(feeds[0].custom_title)
    }

    @Test(expected = HttpException::class)
    fun `updateFeed interval below 5 throws HttpException 400`() = runTest {
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
}
