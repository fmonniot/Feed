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
 * #103 acceptance test: seeds a feed with > 50 articles and verifies the
 * window-vs-badge contract at the **production** window size (0 until 50).
 *
 * BUG-34: the original test used a wider window (0..99) that masked the
 * fact that the production UI only shows the first DEFAULT_PAGE_SIZE (50)
 * rows. With the production window the list is capped at 50 while the
 * badge correctly reflects the global count (55). The contract is:
 *   badge >= list.size  (always)
 *   badge == total unread  (global count)
 *   list.size == min(total, 50)  (capped window)
 *
 * Also verifies that a server-side delete disappears locally after a sync,
 * exercising the tombstone path (deleted_ids → store.deleteByIds) end-to-end.
 *
 * BUG-41: the original server-delete test used repository.deleteFeed() which
 * clears local data immediately via store.deleteByFeedId(), bypassing the
 * tombstone sync path entirely. Fixed to call feedApi.deleteFeed() (server-only
 * delete) and verify articles disappear through the SyncEngine tombstone path.
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
    fun `badge reflects global count while list is capped at production window for All filter`() = runTest {
        rss.enqueueRssFeedWithItems("Large Feed", itemCount = 55)
        val feed = repository.addFeed(rss.baseUrl)
        repository.refresh()

        // BUG-34: use the production window size (0 until 50), not a wider 0..99
        val productionWindow = 0 until 50
        val allArticles = repository.observePage(ArticleFilter.All, productionWindow).first()
        val listSize = allArticles.size
        val badge = repository.observeUnreadCount(ArticleFilter.All).first()

        assertEquals("list must be capped at production window size", 50, listSize)
        assertEquals("badge must reflect global unread count", 55, badge)
        assertTrue("badge must be >= list size", badge >= listSize)

        // Mark a few articles as read and verify badge decreases.
        val toMark = allArticles.take(3).map { it.id.toInt() }
        for (id in toMark) {
            repository.markAsRead(id)
        }
        val badgeAfter = repository.observeUnreadCount(ArticleFilter.All).first()
        val listSizeAfter = repository.observePage(ArticleFilter.All, productionWindow).first().size

        assertEquals("list size unchanged after marking read (still shows read+unread)", listSize, listSizeAfter)
        assertTrue("badge must decrease after marking articles read", badgeAfter < badge)
        assertEquals("badge must equal original minus marked count",
            badge - toMark.size, badgeAfter)
    }

    @Test
    fun `badge reflects global count while list is capped at production window for UnreadOnly filter`() = runTest {
        rss.enqueueRssFeedWithItems("Unread Feed", itemCount = 55)
        repository.addFeed(rss.baseUrl)
        repository.refresh()

        // BUG-34: use the production window size (0 until 50), not a wider 0..99
        val productionWindow = 0 until 50
        val listSize = repository.observePage(ArticleFilter.UnreadOnly, productionWindow).first().size
        val badge = repository.observeUnreadCount(ArticleFilter.UnreadOnly).first()

        assertEquals("list must be capped at production window size", 50, listSize)
        assertEquals("badge must reflect global unread count", 55, badge)
        assertTrue("badge must be >= list size", badge >= listSize)
    }

    @Test
    fun `badge reflects global count while list is capped at production window for ByFeed filter`() = runTest {
        rss.enqueueRssFeedWithItems("Per-Feed", itemCount = 55)
        val feed = repository.addFeed(rss.baseUrl)
        repository.refresh()

        // BUG-34: use the production window size (0 until 50), not a wider 0..99
        val productionWindow = 0 until 50
        val filter = ArticleFilter.ByFeed(feed.id)
        val listSize = repository.observePage(filter, productionWindow).first().size
        val badge = repository.observeUnreadCount(filter).first()

        assertEquals("list must be capped at production window size", 50, listSize)
        assertEquals("badge must reflect global unread count", 55, badge)
        assertTrue("badge must be >= list size", badge >= listSize)
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

        // BUG-41: Delete the feed on the *server only* via the API, without
        // touching the local store.  The old test used repository.deleteFeed()
        // which calls store.deleteByFeedId() — clearing local rows before the
        // sync ever runs, so the tombstone path (deleted_ids → store.deleteByIds)
        // was never exercised.
        //
        // feedApi.deleteFeed() issues DELETE /v1/feeds/{id} which cascades to the
        // feed's articles, firing the articles_seq_ad trigger that writes each
        // article id into the deleted_articles tombstone table.
        feedApi.deleteFeed(feed.id)

        // Articles must still be present locally — the server delete hasn't been
        // synced yet.
        val midAll = repository.observePage(ArticleFilter.All, 0..49).first()
        assertEquals("articles must still be local before sync", 5, midAll.size)

        // Now refresh — this calls SyncEngine which fetches /v1/sync, receives
        // the deleted_ids tombstones, and applies them via store.deleteByIds().
        repository.refresh()

        val afterAll = repository.observePage(ArticleFilter.All, 0..49).first()
        val afterFeed = repository.observePage(ArticleFilter.ByFeed(feed.id), 0..49).first()
        val afterCount = repository.observeUnreadCount(ArticleFilter.All).first()

        assertEquals("all articles must be gone after sync", 0, afterAll.size)
        assertEquals("per-feed articles must be gone after sync", 0, afterFeed.size)
        assertEquals("unread count must be 0 after sync", 0, afterCount)

        // Defense-in-depth: a second sync must not re-introduce deleted articles.
        repository.refresh()
        val afterSecondRefresh = repository.observePage(ArticleFilter.All, 0..49).first()
        assertEquals("articles must stay gone after second sync", 0, afterSecondRefresh.size)
    }
}
