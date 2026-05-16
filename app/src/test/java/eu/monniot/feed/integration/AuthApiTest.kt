package eu.monniot.feed.integration

import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.NetworkModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException

class AuthApiTest {
    @get:Rule
    val server = ServerRule()

    private val cookieJar = InMemoryCookieJar()
    private val authApi by lazy { NetworkModule.createAuthApi(cookieJar) }
    private val feedApi by lazy { NetworkModule.createFeedV1Api(cookieJar) }

    @Test
    fun `login with valid credentials returns username`() = runBlocking {
        val response = authApi.login(LoginRequest("admin", "admin"))
        assertEquals("admin", response.username)
    }

    @Test(expected = HttpException::class)
    fun `login with wrong password throws HttpException`() = runBlocking {
        authApi.login(LoginRequest("admin", "wrongpassword"))
        Unit
    }

    @Test(expected = HttpException::class)
    fun `login with wrong username throws HttpException`() = runBlocking {
        authApi.login(LoginRequest("wronguser", "admin"))
        Unit
    }

    @Test
    fun `successful login lets subsequent authenticated calls succeed`() = runBlocking {
        authApi.login(LoginRequest("admin", "admin"))
        // The cookie jar should now hold the session cookie issued by login.
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
        } catch (e: HttpException) {
            threw = true
            assertEquals(401, e.code())
        }
        assertTrue("expected 401 after logout", threw)
    }
}
