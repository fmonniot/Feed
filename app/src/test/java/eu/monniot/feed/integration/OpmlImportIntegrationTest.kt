package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.russhwolf.settings.SharedPreferencesSettings
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.FeedRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpmlImportIntegrationTest {
    @get:Rule
    val server = ServerRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: FeedDatabase
    private lateinit var client: HttpClient
    private lateinit var viewModel: FeedViewModel

    private val twoFeedOpml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="1.0">
          <head><title>Test</title></head>
          <body>
            <outline text="Alpha Blog" title="Alpha Blog" type="rss"
                     xmlUrl="http://example.com/alpha.rss"/>
            <outline text="Beta News" title="Beta News" type="rss"
                     xmlUrl="http://example.com/beta.rss"/>
          </body>
        </opml>
    """.trimIndent()

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
        val sessionManager = SessionManager()
        val authApi = AuthApi(client)
        val feedApi = FeedApi(client)
        val repository = FeedRepository(feedApi, db.rssItemDao())
        val settings = SharedPreferencesSettings.Factory(context).create("test_opml_settings")
        val serverUrlStore = ServerUrlStore(settings)
        val userPrefs = UserPrefs(settings)
        viewModel = FeedViewModel(repository, authApi, sessionManager, {}, serverUrlStore, userPrefs)

        runBlocking {
            viewModel.login("admin", "admin")
            withTimeout(10_000) { viewModel.isLoggedIn.first { it } }
        }
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `importOpml new feeds updates status with imported count`() = runBlocking {
        viewModel.importOpml(twoFeedOpml)
        withTimeout(10_000) { viewModel.opmlImportStatus.first { it != null } }

        val status = viewModel.opmlImportStatus.value ?: ""
        assertTrue("Expected status to mention 'Imported 2' but was: $status", "Imported 2" in status)
        assertTrue("Expected no failures", viewModel.opmlImportFailures.value.isEmpty())
    }

    @Test
    fun `importOpml new feeds are added to the feed list`() = runBlocking {
        viewModel.importOpml(twoFeedOpml)
        withTimeout(10_000) { viewModel.opmlImportStatus.first { it != null } }

        viewModel.loadFeeds()
        withTimeout(10_000) { viewModel.feedsLoading.first { !it } }

        assertEquals(2, viewModel.feeds.value.size)
    }

    @Test
    fun `reimporting same OPML reports already_exists in status`() = runBlocking {
        // First import — create the feeds
        viewModel.importOpml(twoFeedOpml)
        withTimeout(10_000) { viewModel.opmlImportStatus.first { it != null } }

        // Reset status so we can wait for the second import's result
        viewModel.clearOpmlImportStatus()

        // Second import — same OPML, both feeds already exist
        viewModel.importOpml(twoFeedOpml)
        withTimeout(10_000) { viewModel.opmlImportStatus.first { it != null } }

        val status = viewModel.opmlImportStatus.value ?: ""
        assertTrue(
            "Expected status to mention 'already existed' but was: $status",
            "already existed" in status,
        )
    }
}
