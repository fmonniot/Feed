package eu.monniot.feed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.russhwolf.settings.SharedPreferencesSettings
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Unit tests for [probeSessionWith]:
 *
 * - 401 from server → session cleared (logged out)
 * - Connectivity error (server offline) → persisted session kept intact
 * - Successful 200 → session confirmed as logged-in
 * - 5xx server error → current session state preserved
 *
 * And session-persistence tests for [SessionManager]:
 *
 * - isLoggedIn and username survive a simulated process restart (new SessionManager
 *   over the same Settings store).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProbeSessionTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: HttpClient
    private lateinit var feedApi: FeedApi
    private var serverShutDown = false

    @Before
    fun setUp() {
        serverShutDown = false
        mockServer = MockWebServer()
        mockServer.start()

        client = HttpClient(CIO) {
            expectSuccess = true
            install(HttpCookies) { storage = AcceptAllCookiesStorage() }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(DefaultRequest) { url(mockServer.url("/").toString()) }
        }
        feedApi = FeedApi(client)
    }

    @After
    fun tearDown() {
        client.close()
        if (!serverShutDown) mockServer.shutdown()
    }

    /** Shuts down the mock server and marks it as already-shut-down so tearDown skips it. */
    private fun shutDownServer() {
        mockServer.shutdown()
        serverShutDown = true
    }

    // ------------------------------------------------------------------
    // probeSessionWith behaviour
    // ------------------------------------------------------------------

    @Test
    fun `probeSession with 401 response clears logged-in state`() = runBlocking {
        val sessionManager = SessionManager()
        sessionManager.setLoggedIn(true)

        mockServer.enqueue(MockResponse().setResponseCode(401))

        probeSessionWith(feedApi, sessionManager)

        assertFalse(
            "A 401 response must clear the session",
            sessionManager.isLoggedIn.value,
        )
    }

    @Test
    fun `probeSession with connectivity error keeps persisted logged-in state`() = runBlocking {
        val sessionManager = SessionManager()
        sessionManager.setLoggedIn(true)

        // Shut down the server so all connections fail immediately
        shutDownServer()

        probeSessionWith(feedApi, sessionManager)  // must not throw

        assertTrue(
            "A connectivity error must not clear a persisted logged-in session",
            sessionManager.isLoggedIn.value,
        )
    }

    @Test
    fun `probeSession with connectivity error keeps persisted logged-out state`() = runBlocking {
        val sessionManager = SessionManager()
        sessionManager.setLoggedIn(false)

        shutDownServer()

        probeSessionWith(feedApi, sessionManager)

        assertFalse(
            "A connectivity error on a logged-out session must leave it logged-out",
            sessionManager.isLoggedIn.value,
        )
    }

    @Test
    fun `probeSession with 200 response confirms session logged-in`() = runBlocking {
        val sessionManager = SessionManager()
        sessionManager.setLoggedIn(true)

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":{"total_unread":0}}"""),
        )

        probeSessionWith(feedApi, sessionManager)

        assertTrue(
            "A successful response must confirm the session as logged-in",
            sessionManager.isLoggedIn.value,
        )
    }

    @Test
    fun `probeSession with 500 response keeps current logged-in state`() = runBlocking {
        // 5xx is not a definitive auth failure — keep current state
        val sessionManager = SessionManager()
        sessionManager.setLoggedIn(true)

        mockServer.enqueue(MockResponse().setResponseCode(500))

        probeSessionWith(feedApi, sessionManager)

        assertTrue(
            "A 5xx response must not clear a persisted logged-in session",
            sessionManager.isLoggedIn.value,
        )
    }

    // ------------------------------------------------------------------
    // Session persistence across simulated process restarts
    // ------------------------------------------------------------------

    @Test
    fun `isLoggedIn survives simulated process restart`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefsName = "test_session_persistence_${UUID.randomUUID()}"

        // First "process" — log in and persist
        val settings1 = SharedPreferencesSettings.Factory(context).create(prefsName)
        val sessionManager1 = SessionManager(settings = settings1)
        sessionManager1.setLoggedIn(true)
        sessionManager1.setUsername("alice")

        // Second "process" — new SessionManager over the same prefs
        val settings2 = SharedPreferencesSettings.Factory(context).create(prefsName)
        val sessionManager2 = SessionManager(settings = settings2)

        assertTrue(
            "isLoggedIn must be true after restart when session was persisted",
            sessionManager2.isLoggedIn.value,
        )
    }

    @Test
    fun `username survives simulated process restart`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefsName = "test_username_persistence_${UUID.randomUUID()}"

        val settings1 = SharedPreferencesSettings.Factory(context).create(prefsName)
        val sessionManager1 = SessionManager(settings = settings1)
        sessionManager1.setLoggedIn(true)
        sessionManager1.setUsername("alice")

        val settings2 = SharedPreferencesSettings.Factory(context).create(prefsName)
        val sessionManager2 = SessionManager(settings = settings2)

        assert(sessionManager2.username.value == "alice") {
            "username must be 'alice' after restart, was '${sessionManager2.username.value}'"
        }
    }

    @Test
    fun `logged-out state survives simulated process restart`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefsName = "test_logout_persistence_${UUID.randomUUID()}"

        // First "process" — log in then log out
        val settings1 = SharedPreferencesSettings.Factory(context).create(prefsName)
        val sessionManager1 = SessionManager(settings = settings1)
        sessionManager1.setLoggedIn(true)
        sessionManager1.setLoggedIn(false)

        // Second "process"
        val settings2 = SharedPreferencesSettings.Factory(context).create(prefsName)
        val sessionManager2 = SessionManager(settings = settings2)

        assertFalse(
            "isLoggedIn must be false after restart when session was cleared",
            sessionManager2.isLoggedIn.value,
        )
    }
}
