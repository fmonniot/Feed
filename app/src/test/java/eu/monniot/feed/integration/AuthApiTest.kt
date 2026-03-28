package eu.monniot.feed.integration

import eu.monniot.feed.api.LoginRequest
import eu.monniot.feed.api.NetworkModule
import eu.monniot.feed.api.RefreshRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException

class AuthApiTest {
    @get:Rule
    val server = ServerRule()

    private val authApi by lazy { NetworkModule.createAuthApi() }

    @Test
    fun `login with valid credentials returns tokens`() = runBlocking {
        val response = authApi.login(LoginRequest("admin", "admin"))
        assertNotNull(response.access_token)
        assertNotNull(response.refresh_token)
        assertEquals("Bearer", response.token_type)
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
    fun `refresh with valid token returns new access token`() = runBlocking {
        val loginResponse = authApi.login(LoginRequest("admin", "admin"))
        val refreshResponse = authApi.refresh(RefreshRequest(loginResponse.refresh_token))
        assertNotNull(refreshResponse.access_token)
        assertEquals("Bearer", refreshResponse.token_type)
    }
}
