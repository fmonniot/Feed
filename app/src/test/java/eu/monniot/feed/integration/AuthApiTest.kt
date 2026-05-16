package eu.monniot.feed.integration

import eu.monniot.feed.shared.api.AuthApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AuthApiTest {
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
    }

    @After
    fun tearDown() {
        client.close()
    }

    @Test
    fun `login with valid credentials returns username`() = runBlocking {
        val response = authApi.login(LoginRequest("admin", "admin"))
        assertEquals("admin", response.username)
    }

    @Test(expected = ClientRequestException::class)
    fun `login with wrong password throws ClientRequestException`() = runBlocking {
        authApi.login(LoginRequest("admin", "wrongpassword"))
        Unit
    }

    @Test(expected = ClientRequestException::class)
    fun `login with wrong username throws ClientRequestException`() = runBlocking {
        authApi.login(LoginRequest("wronguser", "admin"))
        Unit
    }

    @Test
    fun `successful login lets subsequent authenticated calls succeed`() = runBlocking {
        authApi.login(LoginRequest("admin", "admin"))
        val unread = feedApi.getUnreadCount()
        assertEquals(0, unread.data.total_unread)
    }

    @Test
    fun `logout clears the session — follow-up calls return 401`() = runBlocking {
        authApi.login(LoginRequest("admin", "admin"))
        authApi.logout()

        var threw = false
        try {
            feedApi.getUnreadCount()
        } catch (e: ClientRequestException) {
            threw = true
            assertEquals(401, e.response.status.value)
        }
        assertTrue("expected 401 after logout", threw)
    }
}
