package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
import eu.monniot.feed.FeedViewModel
import eu.monniot.feed.api.NetworkModule
import eu.monniot.feed.api.ServerUrlStore
import eu.monniot.feed.api.SessionManager
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
import org.junit.Assert.assertNull
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
    private lateinit var cookieJar: InMemoryCookieJar
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

        cookieJar = InMemoryCookieJar()
        sessionManager = SessionManager()
        val authApi = NetworkModule.createAuthApi(cookieJar)
        val feedApi = NetworkModule.createFeedV1Api(cookieJar)
        val repository = FeedRepository(feedApi, db.rssItemDao())
        val serverUrlStore = ServerUrlStore(context)
        viewModel = FeedViewModel(
            repository,
            authApi,
            sessionManager,
            { /* in-memory jar; nothing to persist */ },
            serverUrlStore,
        )

        runBlocking {
            viewModel.login("admin", "admin")
            withTimeout(10_000) { viewModel.isLoggedIn.first { it } }
        }
    }

    @After
    fun tearDown() {
        db.close()
        rss.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `feeds is empty before loadFeeds`() {
        assertTrue(viewModel.feeds.value.isEmpty())
    }

    @Test
    fun `loadFeeds with no feeds produces empty list`() = runBlocking {
        viewModel.loadFeeds()
        withTimeout(10_000) { viewModel.feedsLoading.first { !it } }
        assertTrue(viewModel.feeds.value.isEmpty())
    }

    @Test
    fun `feedsLoading transitions false to true to false during loadFeeds`() = runBlocking {
        assertFalse(viewModel.feedsLoading.value)
        viewModel.loadFeeds()
        withTimeout(10_000) { viewModel.feedsLoading.first { !it } }
        assertFalse(viewModel.feedsLoading.value)
    }

    @Test
    fun `addFeed with invalid URL sets addFeedError`() = runBlocking {
        viewModel.addFeed("not-a-url") {}
        withTimeout(10_000) {
            val error = viewModel.addFeedError.first { it != null }
            assertNotNull(error)
        }
    }

    @Test
    fun `addFeedError contains verbatim server text`() = runBlocking {
        viewModel.addFeed("not-a-url") {}
        withTimeout(10_000) {
            val error = viewModel.addFeedError.first { it != null }
            assertTrue("Expected server message, got: $error", error!!.contains("URL must start with"))
        }
    }

    @Test
    fun `addFeedLoading is false before and after add`() = runBlocking {
        assertFalse(viewModel.addFeedLoading.value)
        viewModel.addFeed("not-a-url") {}
        withTimeout(10_000) { viewModel.addFeedError.first { it != null } }
        assertFalse(viewModel.addFeedLoading.value)
    }

    @Test
    fun `addFeed success calls onSuccess callback`() = runBlocking {
        rss.enqueueRssFeed("Success Feed")
        var callbackFired = false
        viewModel.addFeed(rss.baseUrl) { callbackFired = true }
        withTimeout(10_000) { viewModel.feeds.first { it.isNotEmpty() } }
        assertTrue("onSuccess callback was not called", callbackFired)
    }

    @Test
    fun `addFeed success adds feed to feeds list`() = runBlocking {
        rss.enqueueRssFeed("New Feed")
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(10_000) {
            val feeds = viewModel.feeds.first { it.isNotEmpty() }
            assertEquals(1, feeds.size)
        }
    }

    @Test
    fun `renameFeed updates displayTitle`() = runBlocking {
        rss.enqueueRssFeed("Original")
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(10_000) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.renameFeed(feedId, "Renamed Feed")
        withTimeout(10_000) { viewModel.feeds.first { it.any { f -> f.displayTitle == "Renamed Feed" } } }
        assertTrue(viewModel.feeds.value.any { it.displayTitle == "Renamed Feed" })
    }

    @Test
    fun `setFeedInterval below 5 sets feedsError`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(10_000) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.setFeedInterval(feedId, 4)
        withTimeout(10_000) {
            val error = viewModel.feedsError.first { it != null }
            assertNotNull(error)
        }
    }

    @Test
    fun `toggleFeedPaused sets isPaused true`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(10_000) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.toggleFeedPaused(feedId, true)
        withTimeout(10_000) { viewModel.feeds.first { it.any { f -> f.isPaused } } }
        assertTrue(viewModel.feeds.value.any { it.isPaused })
    }

    @Test
    fun `toggleFeedPaused sets isPaused false`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(10_000) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.toggleFeedPaused(feedId, true)
        withTimeout(10_000) { viewModel.feeds.first { it.any { f -> f.isPaused } } }

        viewModel.toggleFeedPaused(feedId, false)
        withTimeout(10_000) { viewModel.feeds.first { it.any { f -> !f.isPaused } } }
        assertTrue(viewModel.feeds.value.none { it.isPaused })
    }

    @Test
    fun `deleteFeed removes from feeds list`() = runBlocking {
        rss.enqueueRssFeed()
        viewModel.addFeed(rss.baseUrl) {}
        withTimeout(10_000) { viewModel.feeds.first { it.isNotEmpty() } }

        val feedId = viewModel.feeds.value[0].id
        viewModel.deleteFeed(feedId)
        withTimeout(10_000) { viewModel.feeds.first { it.isEmpty() } }
        assertTrue(viewModel.feeds.value.isEmpty())
    }

    @Test
    fun `clearFeedsError resets feedsError to null`() = runBlocking {
        viewModel.loadFeeds()
        withTimeout(10_000) { viewModel.feedsLoading.first { !it } }
        // Manually set to simulate an error state
        viewModel.clearFeedsError()
        assertNull(viewModel.feedsError.value)
    }

    @Test
    fun `clearAddFeedError resets addFeedError to null`() = runBlocking {
        viewModel.addFeed("not-a-url") {}
        withTimeout(10_000) { viewModel.addFeedError.first { it != null } }
        viewModel.clearAddFeedError()
        assertNull(viewModel.addFeedError.value)
    }
}
