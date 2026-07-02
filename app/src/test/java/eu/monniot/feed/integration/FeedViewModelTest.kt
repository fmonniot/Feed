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
import eu.monniot.feed.shared.UiState
import eu.monniot.feed.shared.api.Article
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
import kotlinx.coroutines.flow.filterNotNull
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
    private lateinit var store: RoomArticleStore

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
        store = RoomArticleStore(db, db.articleStoreDao())
        val repository = SharedFeedRepository(feedApi, store, SyncEngine(feedApi, store))
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

        withTimeout(INTEGRATION_WAIT_MS) {
            val loggedIn = viewModel.isLoggedIn.first { it }
            assertTrue(loggedIn)
        }
    }

    @Test
    fun `login with bad credentials sets loginError`() = runBlocking {
        viewModel.login("admin", "wrongpassword")

        withTimeout(INTEGRATION_WAIT_MS) {
            val error = viewModel.loginError.first { it != null }
            assertNotNull(error)
        }
    }

    @Test
    fun `logout clears isLoggedIn`() = runBlocking {
        viewModel.login("admin", "admin")
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.isLoggedIn.first { it } }

        viewModel.logout()

        withTimeout(INTEGRATION_WAIT_MS) {
            val loggedIn = viewModel.isLoggedIn.first { !it }
            assertFalse(loggedIn)
        }
        val articlesAfter = db.articleStoreDao().observePageAll(100, 0).first()
        assertTrue("sync_articles must be empty after logout", articlesAfter.isEmpty())
    }

    @Test
    fun `refresh completes without error when logged in`() = runBlocking {
        viewModel.login("admin", "admin")
        withTimeout(INTEGRATION_WAIT_MS) { viewModel.isLoggedIn.first { it } }

        viewModel.refresh()

        withTimeout(INTEGRATION_WAIT_MS) { viewModel.isRefreshing.first { !it } }

        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `clearError resets uiState to Idle`() {
        viewModel.clearError()
        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `articleItems is null initially`() {
        assertTrue(viewModel.articleItems.value == null || viewModel.articleItems.value!!.isEmpty())
    }

    /**
     * End-to-end regression for the Unread-tab inbox-zero bug: the newest
     * DEFAULT_PAGE_SIZE articles are all read, and older unread articles exist
     * past that window. Before the fix, FeedScreen always windowed
     * ArticleFilter.All and filtered client-side, so the Unread tab's window
     * held zero unread rows and rendered "caught up" despite a nonzero badge.
     * selectFeed(showAll = false) must switch the real Room-backed store query
     * to ArticleFilter.UnreadOnly so the older unread articles surface.
     */
    @Test
    fun `selectFeed unread surfaces unread articles beyond the newest read window`() = runBlocking {
        val pageSize = eu.monniot.feed.shared.FeedViewModel.DEFAULT_PAGE_SIZE
        fun article(id: Int, published: Long, isRead: Boolean) = Article(
            id = id,
            feed_id = 1,
            guid = "guid-$id",
            title = "Article $id",
            content = null,
            link = null,
            author = null,
            published = published,
            is_read = isRead,
            fetched_at = null,
            link_status = null,
            link_checked_at = null,
            seq = id.toLong(),
        )
        val newestRead = (1..pageSize).map { article(id = it, published = 1_000_000L + it, isRead = true) }
        val olderUnreadIds = (1..3).map { pageSize + it }
        val olderUnread = olderUnreadIds.map { article(id = it, published = it.toLong(), isRead = false) }
        store.upsert(newestRead + olderUnread)

        viewModel.selectFeed(feedId = null, showAll = false)

        withTimeout(INTEGRATION_WAIT_MS) {
            val items = viewModel.articleItems.filterNotNull().first { it.isNotEmpty() }
            assertEquals(olderUnreadIds.map { it.toString() }.toSet(), items.map { it.id }.toSet())
        }
    }

    /**
     * The article just opened from the Unread tab must stay resolvable via
     * [FeedViewModel.articleItems] (which [selectArticle] backs with
     * keepArticleId) even after it's marked read — otherwise MainActivity's
     * reader route (`articleItems?.firstOrNull { it.id == articleId }`) would
     * find nothing and render a blank screen.
     */
    @Test
    fun `selected article stays resolvable via articleItems after being marked read`() = runBlocking {
        val article = Article(
            id = 1, feed_id = 1, guid = "guid-1", title = "Article 1",
            content = null, link = null, author = null, published = 1L,
            is_read = false, fetched_at = null, link_status = null, link_checked_at = null, seq = 1L,
        )
        store.upsert(listOf(article))

        viewModel.selectFeed(feedId = null, showAll = false)
        viewModel.selectArticle("1")
        // Drive the store directly to simulate the write markAsRead() performs
        // after its server call succeeds — the mark-as-read network flow itself
        // is covered elsewhere; this test is only about the store filter + the
        // selectArticle/selectFeed wiring in the Android FeedViewModel wrapper.
        store.markRead(1, isRead = true)

        withTimeout(INTEGRATION_WAIT_MS) {
            val resolved = viewModel.articleItems.filterNotNull().first { it.isNotEmpty() }
                .firstOrNull { it.id == "1" }
            assertNotNull("reader route must still resolve the just-read article", resolved)
            assertTrue(resolved!!.isRead)
        }
    }
}
