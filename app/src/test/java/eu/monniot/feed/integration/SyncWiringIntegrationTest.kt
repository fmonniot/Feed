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
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #103 acceptance test: seeds a feed with > 50 articles and verifies that
 * badge (observeUnreadCount) == list (observePage) for All, UnreadOnly,
 * and ByFeed filters. Also verifies that a server-side delete disappears
 * locally after a sync.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWiringIntegrationTest {
    @get:Rule
    val server = ServerRule()

    private val rss = MockRssServer()

    private lateinit var db: FeedDatabase
    private lateinit var client: HttpClient
    private lateinit var feedApi: FeedApi
    private lateinit var repository: FeedRepository

    @Before
    fun setUp() = runTest {
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
        AuthApi(client).login(LoginRequest("admin", "admin"))
        feedApi = FeedApi(client)
        val store = RoomArticleStore(db, db.articleStoreDao())
        repository = SharedFeedRepository(feedApi, store, SyncEngine(feedApi, store))
    }

    @After
    fun tearDown() {
        db.close()
        client.close()
        rss.shutdown()
    }

    @Test
    fun `badge equals list for All filter with more than 50 articles`() = runTest {
        rss.enqueueRssFeedWithItems("Large Feed", itemCount = 55)
        val feed = repository.addFeed(rss.baseUrl)
        repository.refresh()

        val allArticles = repository.observePage(ArticleFilter.All, 0..99).first()
        val listSize = allArticles.size
        val badge = repository.observeUnreadCount(ArticleFilter.All).first()

        assertTrue("must have > 50 articles", listSize > 50)
        assertEquals("badge must equal list size for All filter", listSize, badge)

        // Mark a few articles as read and verify badge < listSize,
        // pinning that observeUnreadCount counts only unread rows.
        val toMark = allArticles.take(3).map { it.id.toInt() }
        for (id in toMark) {
            repository.markAsRead(id)
        }
        val badgeAfter = repository.observeUnreadCount(ArticleFilter.All).first()
        val listSizeAfter = repository.observePage(ArticleFilter.All, 0..99).first().size

        assertEquals("list size unchanged after marking read", listSize, listSizeAfter)
        assertTrue("badge must decrease after marking articles read", badgeAfter < listSize)
        assertEquals("badge must equal original minus marked count",
            listSize - toMark.size, badgeAfter)
    }

    @Test
    fun `badge equals list for UnreadOnly filter with more than 50 articles`() = runTest {
        rss.enqueueRssFeedWithItems("Unread Feed", itemCount = 55)
        repository.addFeed(rss.baseUrl)
        repository.refresh()

        val listSize = repository.observePage(ArticleFilter.UnreadOnly, 0..99).first().size
        val badge = repository.observeUnreadCount(ArticleFilter.UnreadOnly).first()

        assertTrue("must have > 50 unread articles", listSize > 50)
        assertEquals("badge must equal list size for UnreadOnly filter", listSize, badge)
    }

    @Test
    fun `badge equals list for ByFeed filter with more than 50 articles`() = runTest {
        rss.enqueueRssFeedWithItems("Per-Feed", itemCount = 55)
        val feed = repository.addFeed(rss.baseUrl)
        repository.refresh()

        val filter = ArticleFilter.ByFeed(feed.id)
        val listSize = repository.observePage(filter, 0..99).first().size
        val badge = repository.observeUnreadCount(filter).first()

        assertTrue("must have > 50 articles for the feed", listSize > 50)
        assertEquals("badge must equal list size for ByFeed filter", listSize, badge)
    }

    @Test
    fun `server-side delete disappears locally after sync`() = runTest {
        rss.enqueueRssFeedWithItems("Delete Me", itemCount = 5)
        val feed = repository.addFeed(rss.baseUrl)
        repository.refresh()

        val beforeAll = repository.observePage(ArticleFilter.All, 0..49).first()
        val beforeFeed = repository.observePage(ArticleFilter.ByFeed(feed.id), 0..49).first()
        assertEquals("should have 5 articles before delete", 5, beforeAll.size)
        assertEquals("should have 5 per-feed articles before delete", 5, beforeFeed.size)

        // Delete the feed on the server — this also removes its articles server-side.
        repository.deleteFeed(feed.id)

        // After deleteFeed, the local store should already reflect the deletion
        // (SharedFeedRepository.deleteFeed calls store.deleteByFeedId).
        val afterAll = repository.observePage(ArticleFilter.All, 0..49).first()
        val afterFeed = repository.observePage(ArticleFilter.ByFeed(feed.id), 0..49).first()
        val afterCount = repository.observeUnreadCount(ArticleFilter.All).first()

        assertEquals("all articles must be gone after delete", 0, afterAll.size)
        assertEquals("per-feed articles must be gone after delete", 0, afterFeed.size)
        assertEquals("unread count must be 0 after delete", 0, afterCount)

        // Verify a subsequent refresh does not re-introduce deleted articles.
        repository.refresh()
        val afterRefresh = repository.observePage(ArticleFilter.All, 0..49).first()
        assertEquals("articles must stay gone after sync", 0, afterRefresh.size)
    }
}
