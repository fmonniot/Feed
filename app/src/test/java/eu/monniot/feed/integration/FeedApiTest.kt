package eu.monniot.feed.integration

import eu.monniot.feed.api.FeedAddRequest
import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.NetworkModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException

class FeedApiTest {
    @get:Rule
    val server = ServerRule()

    private val cookieJar = InMemoryCookieJar()
    private val authApi by lazy { NetworkModule.createAuthApi(cookieJar) }
    private val feedApi by lazy { NetworkModule.createFeedV1Api(cookieJar) }

    @Before
    fun setUp() {
        runBlocking { authApi.login(LoginRequest("admin", "admin")) }
    }

    @Test
    fun `health check returns healthy`() = runBlocking {
        val response = feedApi.checkHealth()
        assertEquals("healthy", response.status)
        assertEquals("connected", response.database)
    }

    @Test
    fun `feeds list is empty initially`() = runBlocking {
        val response = feedApi.getFeeds()
        assertTrue(response.data.isEmpty())
    }

    @Test(expected = HttpException::class)
    fun `add feed with invalid URL returns error`() = runBlocking {
        // The server validates the feed URL by trying to fetch it
        feedApi.addFeed(FeedAddRequest("https://example.com/nonexistent-feed.xml"))
        Unit
    }

    @Test
    fun `unread count is zero initially`() = runBlocking {
        val response = feedApi.getUnreadCount()
        assertEquals(0, response.data.total_unread)
    }

    @Test
    fun `articles list is empty initially`() = runBlocking {
        val response = feedApi.getArticles()
        assertTrue(response.data.isEmpty())
    }

    @Test
    fun `stats returns valid structure`() = runBlocking {
        val response = feedApi.getStats()
        assertNotNull(response.data.feeds)
        assertNotNull(response.data.articles)
        assertNotNull(response.data.trends)
    }
}
