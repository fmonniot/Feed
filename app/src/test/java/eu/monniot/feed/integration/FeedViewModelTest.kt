package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.UiState
import eu.monniot.feed.api.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
    private lateinit var tokenManager: InMemoryTokenManager
    private lateinit var viewModel: FeedViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        tokenManager = InMemoryTokenManager()
        val authApi = NetworkModule.createAuthApi()
        val feedApi = NetworkModule.createFeedV1Api(tokenManager, authApi)
        val repository = FeedRepository(feedApi, db.rssItemDao())
        viewModel = FeedViewModel(repository, authApi, tokenManager)
    }

    @After
    fun tearDown() {
        db.close()
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
        withTimeout(10_000) {
            viewModel.isLoggedIn.first { it }
        }

        viewModel.logout()

        withTimeout(10_000) {
            val loggedIn = viewModel.isLoggedIn.first { !it }
            assertFalse(loggedIn)
        }
    }

    @Test
    fun `refresh completes without error when logged in`() = runBlocking {
        viewModel.login("admin", "admin")
        withTimeout(10_000) {
            viewModel.isLoggedIn.first { it }
        }

        viewModel.refresh()

        withTimeout(10_000) {
            viewModel.isRefreshing.first { !it }
        }

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
