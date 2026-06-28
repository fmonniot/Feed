package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.russhwolf.settings.SharedPreferencesSettings
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.shared.SharedFeedRepository
import eu.monniot.feed.shared.sync.SyncEngine
import eu.monniot.feed.store.RoomArticleStore
import eu.monniot.feed.FeedViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Feed rename/interval/pause/delete and error-clearing coverage. Split out from
// FeedViewModelFeedsTest so the two halves run in separate forks (a single
// class can't span forks, so the combined class pinned the CI critical path).
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeedViewModelFeedManagementTest {
    @get:Rule
    val server = ServerRule()

    private val rss = MockRssServer()
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: FeedDatabase
    private lateinit var client: HttpClient
    private lateinit var sessionManager: SessionManager
    private lateinit var viewModel: FeedViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        rss.start()

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
        val store = RoomArticleStore(db, db.articleStoreDao())
        val repository = SharedFeedRepository(feedApi, store, SyncEngine(feedApi, store))
        val settings = SharedPreferencesSettings.Factory(context).create("test_settings")
        val serverUrlStore = ServerUrlStore(settings)
        val userPrefs = UserPrefs(settings)
        viewModel = FeedViewModel(
            repository,
            authApi,
            sessionManager,
            { /* in-memory; nothing to persist */ },
            serverUrlStore,
            userPrefs,
        )

        runBlocking {
            viewModel.login("admin", "admin")
            withTimeout(INTEGRATION_WAIT_MS) { viewModel.isLoggedIn.first { it } }
        }
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
        rss.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `renameFeed updates displayTitle`() = runBlocking {
        rss.enqueueRssFeed("Original")
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.renameFeed(feedId, "Renamed Feed")
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.any { f -> f.displayTitle == "Renamed Feed" } } }
        assertTrue(viewModel.feeds.value.any { it.displayTitle == "Renamed Feed" })
    }

    @Test
    fun `setFeedInterval below 5 sets feedsError`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.setFeedInterval(feedId, 4)
        withTimeout(INTEGRATION_WAIT_MS) {
            val error = viewModel.feedsError.first { it != null }
            assertNotNull(error)
        }
    }

    @Test
    fun `toggleFeedPaused sets isPaused true`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.toggleFeedPaused(feedId, true)
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.any { f -> f.isPaused } } }
        assertTrue(viewModel.feeds.value.any { it.isPaused })
    }

    @Test
    fun `toggleFeedPaused sets isPaused false`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.toggleFeedPaused(feedId, true)
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.any { f -> f.isPaused } } }

        viewModel.toggleFeedPaused(feedId, false)
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.any { f -> !f.isPaused } } }
        assertTrue(viewModel.feeds.value.none { it.isPaused })
    }

    @Test
    fun `deleteFeed removes from feeds list`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.deleteFeed(feedId)
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feeds.first { it.isEmpty() } }
        assertTrue(viewModel.feeds.value.isEmpty())
    }

    @Test
    fun `clearFeedsError resets feedsError to null`() = runBlocking {
        viewModel.loadFeeds()
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.feedsLoading.first { !it } }
        viewModel.clearFeedsError()
        assertNull(viewModel.feedsError.value)
    }

    @Test
    fun `clearAddFeedError resets addFeedError to null`() = runBlocking {
        viewModel.addFeed("not-a-url") {}
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.addFeedError.first { it != null } }
        viewModel.clearAddFeedError()
        assertNull(viewModel.addFeedError.value)
    }
}
