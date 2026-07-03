package eu.monniot.feed.integration

import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedAddRequest
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.LoginRequest
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test

class FeedApiTest {
    companion object {
        // One Rust server + one HttpClient(CIO) + one login per class (ticket #96),
        // instead of per test. resetServerFeeds() in @After restores the empty-server
        // state the initial-state assertions below depend on.
        @get:ClassRule
        @JvmStatic
        val server = ServerRule()

        private lateinit var client: HttpClient
        lateinit var authApi: AuthApi
        lateinit var feedApi: FeedApi

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            client = newTestClient(server.baseUrl)
            authApi = AuthApi(client)
            feedApi = FeedApi(client)
            runBlocking { authApi.login(LoginRequest("admin", "admin")) }
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            client.close()
        }
    }

    @After
    fun tearDown() {
        runBlocking { resetServerFeeds(feedApi) }
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

    @Test(expected = ClientRequestException::class)
    fun `add feed with invalid URL returns error`() = runBlocking {
        feedApi.addFeed(FeedAddRequest("https://example.com/nonexistent-feed.xml"))
        Unit
    }

    @Test
    fun `unread count is zero initially via stats`() = runBlocking {
        val response = feedApi.getStats()
        assertEquals(0, response.data.articles.unread)
    }

    @Test
    fun `sync returns empty delta initially`() = runBlocking {
        val response = feedApi.sync(since = 0, limit = 500)
        assertTrue(response is eu.monniot.feed.shared.api.SyncResponse.Delta)
        val delta = response as eu.monniot.feed.shared.api.SyncResponse.Delta
        assertTrue(delta.articles.isEmpty())
    }

    @Test
    fun `stats returns valid structure`() = runBlocking {
        val response = feedApi.getStats()
        assertNotNull(response.data.feeds)
        assertNotNull(response.data.articles)
        assertNotNull(response.data.trends)
    }
}
