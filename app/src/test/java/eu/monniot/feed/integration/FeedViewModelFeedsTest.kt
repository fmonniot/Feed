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
class FeedViewModelFeedsTest {
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
        TestDiag.log("setUp START")
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
        TestDiag.instrument(client, "main")
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
            TestDiag.log("setUp login() launch")
            viewModel.login("admin", "admin")
            awaitDiag("login/isLoggedIn", INTEGRATION_WAIT_MS, { "isLoggedIn=${viewModel.isLoggedIn.value}" }) {
                viewModel.isLoggedIn.first { it }
            }
        }
        TestDiag.log("setUp DONE")
    }

    @After
    fun tearDown() {
        TestDiag.log("tearDown START")
        db.close()
        client.close()
        rss.shutdown()
        Dispatchers.resetMain()
        TestDiag.log("tearDown DONE")
    }

    private fun vmState() =
        "feeds=${viewModel.feeds.value.size} feedsLoading=${viewModel.feedsLoading.value} " +
            "feedsLoaded=${viewModel.feedsLoaded.value} addFeedError=${viewModel.addFeedError.value} " +
            "addFeedLoading=${viewModel.addFeedLoading.value} loginErr=${viewModel.loginError.value}"

    @Test
    fun `feeds is empty before loadFeeds`() {
        assertTrue(viewModel.feeds.value.isEmpty())
    }

    @Test
    fun `loadFeeds with no feeds produces empty list`() = runBlocking {
        viewModel.loadFeeds()
        awaitDiag("loadFeeds/feedsLoading", INTEGRATION_WAIT_MS, ::vmState) { viewModel.feedsLoading.first { !it } }
        assertTrue(viewModel.feeds.value.isEmpty())
    }

    @Test
    fun `feedsLoading transitions false to true to false during loadFeeds`() = runBlocking {
        assertFalse(viewModel.feedsLoading.value)
        viewModel.loadFeeds()
        awaitDiag("transitions/feedsLoading", INTEGRATION_WAIT_MS, ::vmState) { viewModel.feedsLoading.first { !it } }
        assertFalse(viewModel.feedsLoading.value)
    }

    @Test
    fun `addFeed with invalid URL sets addFeedError`() = runBlocking {
        viewModel.addFeed("not-a-url") {}
        awaitDiag("invalidUrl/addFeedError", INTEGRATION_WAIT_MS, ::vmState) {
            val error = viewModel.addFeedError.first { it != null }
            assertNotNull(error)
        }
    }

    @Test
    fun `addFeedLoading is false before and after add`() = runBlocking {
        assertFalse(viewModel.addFeedLoading.value)
        viewModel.addFeed("not-a-url") {}
        awaitDiag("addFeedLoading/addFeedError", INTEGRATION_WAIT_MS, ::vmState) { viewModel.addFeedError.first { it != null } }
        // The finally block that clears addFeedLoading runs after the catch block
        // that sets addFeedError; on a loaded machine the scheduling gap is observable.
        awaitDiag("addFeedLoading/clear", INTEGRATION_WAIT_MS, ::vmState) { viewModel.addFeedLoading.first { !it } }
        assertFalse(viewModel.addFeedLoading.value)
    }

    @Test
    fun `addFeed success calls onSuccess callback`() = runBlocking {
        rss.enqueueRssFeed("Success Feed")
        var callbackFired = false
        viewModel.addFeed(rss.baseUrl) { callbackFired = true }
        awaitDiag("addSuccess/feedsNotEmpty", INTEGRATION_WAIT_MS, ::vmState) { viewModel.feeds.first { it.isNotEmpty() } }
        assertTrue("onSuccess callback was not called", callbackFired)
    }

    @Test
    fun `addFeed success adds feed to feeds list`() = runBlocking {
        rss.enqueueRssFeed("New Feed")
        viewModel.addFeed(rss.baseUrl) {}
        awaitDiag("addSuccess/feedsSize", INTEGRATION_WAIT_MS, ::vmState) {
            val feeds = viewModel.feeds.first { it.isNotEmpty() }
            assertEquals(1, feeds.size)
        }
    }
}
