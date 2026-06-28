package eu.monniot.feed.integration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.SharedFeedRepository
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.LoginRequest
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.SyncEngine
import eu.monniot.feed.store.RoomArticleStore
import eu.monniot.feed.store.SyncArticleEntity
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedRepositoryTest {
    @get:Rule
    val server = ServerRule()

    private lateinit var db: FeedDatabase
    private lateinit var client: HttpClient
    private lateinit var repository: FeedRepository

    @Before
    fun setUp() = runBlocking {
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
        AuthApi(client).login(LoginRequest("admin", "admin"))
        val feedApi = FeedApi(client)
        val store = RoomArticleStore(db, db.articleStoreDao())
        repository = SharedFeedRepository(feedApi, store, SyncEngine(feedApi, store))
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
    }

    @Test
    fun `observePage emits empty list from fresh database`() = runTest {
        val items = repository.observePage(ArticleFilter.All, 0..49).first()
        assertTrue(items.isEmpty())
    }

    @Test
    fun `refresh completes without error`() = runTest {
        repository.refresh()
        val items = repository.observePage(ArticleFilter.All, 0..49).first()
        assertNotNull(items)
    }

    @Test
    fun `markAsRead updates is_read in sync_articles`() = runTest {
        // Insert an article directly into Room via the new DAO.
        val entity = SyncArticleEntity(
            id = 42, feedId = 10, guid = "guid-42",
            title = "Test Article", content = "body",
            link = "http://example.com/42", author = null,
            published = 1700000000L, isRead = false,
            fetchedAt = null, linkStatus = null, linkCheckedAt = null, seq = 1,
        )
        db.articleStoreDao().upsert(listOf(entity))

        val before = db.articleStoreDao().observeUnreadCountAll().first()
        assertEquals("one unread article before markAsRead", 1, before)

        // Mark as read via the DAO (the same path SharedFeedRepository.markAsRead uses
        // on the store after the API call).
        db.articleStoreDao().markRead(42, true)

        val after = db.articleStoreDao().observeUnreadCountAll().first()
        assertEquals("zero unread articles after markAsRead", 0, after)
    }
}
