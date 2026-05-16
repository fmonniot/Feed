package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.russhwolf.settings.SharedPreferencesSettings
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeedViewModelTest {
    @get:Rule
    val server = ServerRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: FeedDatabase
    private lateinit var client: HttpClient
    private lateinit var sessionManager: SessionManager
    private lateinit var viewModel: FeedViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

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
        sessionManager = SessionManager()
        val authApi = AuthApi(client)
        val feedApi = FeedApi(client)
        val repository = FeedRepository(feedApi, db.rssItemDao())
        val settings = SharedPreferencesSettings.Factory(context).create("test_settings")
        val serverUrlStore = ServerUrlStore(settings)
        val userPrefs = UserPrefs(settings)
        viewModel = FeedViewModel(
            repository,
            authApi,
            sessionManager,
            { /* no cookie jar to clear in tests */ },
            serverUrlStore,
            userPrefs,
        )
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `initially not logged in`() {
        assertFalse(viewModel.isLoggedIn.value)
    }

    @Test
    fun `login with valid credentials sets isLoggedIn to true`() = runBlocking {
        viewModel.login("admin", "admin")

        withTimeout(10_000) {
            val loggedIn = viewModel.isLoggedIn.first { it }
            assertTrue(loggedIn)
        }
    }

    @Test
    fun `login with bad credentials sets loginError`() = runBlocking {
        viewModel.login("admin", "wrongpassword")

        withTimeout(10_000) {
            val error = viewModel.loginError.first { it != null }
            assertNotNull(error)
        }
    }

    @Test
    fun `logout clears isLoggedIn`() = runBlocking {
        viewModel.login("admin", "admin")
        withTimeout(10_000) { viewModel.isLoggedIn.first { it } }

        viewModel.logout()

        withTimeout(10_000) {
            val loggedIn = viewModel.isLoggedIn.first { !it }
            assertFalse(loggedIn)
        }
    }

    @Test
    fun `refresh completes without error when logged in`() = runBlocking {
        viewModel.login("admin", "admin")
        withTimeout(10_000) { viewModel.isLoggedIn.first { it } }

        viewModel.refresh()

        withTimeout(10_000) { viewModel.isRefreshing.first { !it } }

        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `clearError resets uiState to Idle`() {
        viewModel.clearError()
        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `items is empty initially`() {
        assertTrue(viewModel.items.value.isEmpty())
    }
}
