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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FeedApiTest {
    @get:Rule
    val server = ServerRule()

    private lateinit var client: HttpClient
    private lateinit var authApi: AuthApi
    private lateinit var feedApi: FeedApi

    @Before
    fun setUp() {
        client = HttpClient(CIO) {
            expectSuccess = true
            install(HttpCookies) { storage = AcceptAllCookiesStorage() }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(DefaultRequest) { url(server.baseUrl) }
        }
        authApi = AuthApi(client)
        feedApi = FeedApi(client)
        runBlocking { authApi.login(LoginRequest("admin", "admin")) }
    }

    @After
    fun tearDown() {
        client.close()
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
