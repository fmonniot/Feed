package eu.monniot.feed.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.sync.ArticleFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [RoomArticleStore] using an in-memory Room database.
 *
 * Covers the full [eu.monniot.feed.shared.sync.ArticleStore] contract:
 * upsert-by-id, deleteByIds, observeUnreadCount, observePage ordering,
 * cursor/setCursor round-trip, and clear().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomArticleStoreTest {

    private lateinit var db: FeedDatabase
    private lateinit var store: RoomArticleStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomArticleStore(db, db.articleStoreDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- Helpers ----

    private fun article(
        id: Int,
        feedId: Int = 1,
        title: String? = "Article $id",
        isRead: Boolean = false,
        published: Long? = 1_000_000L + id,
        seq: Long = id.toLong(),
    ) = Article(
        id = id,
        feed_id = feedId,
        guid = "guid-$id",
        title = title,
        content = "content-$id",
        link = "https://example.com/$id",
        author = "author-$id",
        published = published,
        is_read = isRead,
        fetched_at = 2_000_000L + id,
        link_status = null,
        link_checked_at = null,
        seq = seq,
    )

    // ---- upsert ----

    @Test
    fun upsert_insertsNewArticles() = runTest {
        val articles = listOf(article(1), article(2), article(3))
        store.upsert(articles)

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(3, page.size)
        assertEquals(setOf(1, 2, 3), page.map { it.id }.toSet())
    }

    @Test
    fun upsert_replacesByIdOnConflict() = runTest {
        // Insert original
        store.upsert(listOf(article(1, isRead = false)))
        val before = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(false, before.single().is_read)

        // Upsert with changed is_read
        store.upsert(listOf(article(1, isRead = true)))
        val after = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, after.size)
        assertEquals(true, after.single().is_read)
    }

    @Test
    fun upsert_preservesAllFields() = runTest {
        val original = Article(
            id = 42,
            feed_id = 7,
            guid = "unique-guid",
            title = "My Title",
            content = "<p>HTML content</p>",
            link = "https://example.com/42",
            author = "Jane Doe",
            published = 1_700_000_000L,
            is_read = false,
            fetched_at = 1_700_001_000L,
            link_status = 200,
            link_checked_at = 1_700_002_000L,
            seq = 99,
        )
        store.upsert(listOf(original))

        val retrieved = store.observePage(ArticleFilter.All, 0..99).first().single()
        assertEquals(original.id, retrieved.id)
        assertEquals(original.feed_id, retrieved.feed_id)
        assertEquals(original.guid, retrieved.guid)
        assertEquals(original.title, retrieved.title)
        assertEquals(original.content, retrieved.content)
        assertEquals(original.link, retrieved.link)
        assertEquals(original.author, retrieved.author)
        assertEquals(original.published, retrieved.published)
        assertEquals(original.is_read, retrieved.is_read)
        assertEquals(original.fetched_at, retrieved.fetched_at)
        assertEquals(original.link_status, retrieved.link_status)
        assertEquals(original.link_checked_at, retrieved.link_checked_at)
        assertEquals(original.seq, retrieved.seq)
    }

    // ---- markRead ----

    @Test
    fun markRead_togglesReadStateAndBadge() = runTest {
        // Seed two unread articles
        store.upsert(listOf(
            article(1, isRead = false),
            article(2, isRead = false),
        ))
        assertEquals(2, store.observeUnreadCount(ArticleFilter.All).first())

        // Mark article 1 as read
        store.markRead(1, true)

        // Read state should be toggled
        val page1 = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(true, page1.first { it.id == 1 }.is_read)
        assertEquals(false, page1.first { it.id == 2 }.is_read)

        // Unread badge should decrement
        assertEquals(1, store.observeUnreadCount(ArticleFilter.All).first())

        // Toggle back to unread
        store.markRead(1, false)

        val page2 = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(false, page2.first { it.id == 1 }.is_read)
        assertEquals(2, store.observeUnreadCount(ArticleFilter.All).first())
    }

    // ---- deleteByFeedId ----

    @Test
    fun deleteByFeedId_removesOnlyThatFeed() = runTest {
        // Seed articles across three feeds
        store.upsert(listOf(
            article(1, feedId = 10, isRead = false),
            article(2, feedId = 10, isRead = true),
            article(3, feedId = 20, isRead = false),
            article(4, feedId = 30, isRead = false),
        ))
        assertEquals(4, store.observePage(ArticleFilter.All, 0..99).first().size)

        // Delete feed 10
        store.deleteByFeedId(10)

        // Only feed 10's articles are gone
        val remaining = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(2, remaining.size)
        assertEquals(setOf(3, 4), remaining.map { it.id }.toSet())

        // Unread badge reflects the deletion (was 3, now 2 after removing feed 10's 1 unread)
        assertEquals(2, store.observeUnreadCount(ArticleFilter.All).first())
    }

    // ---- deleteByIds ----

    @Test
    fun deleteByIds_removesMatchingRows() = runTest {
        store.upsert(listOf(article(1), article(2), article(3)))
        store.deleteByIds(listOf(1L, 3L))

        val remaining = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, remaining.size)
        assertEquals(2, remaining.single().id)
    }

    @Test
    fun deleteByIds_noOpForNonexistentIds() = runTest {
        store.upsert(listOf(article(1)))
        store.deleteByIds(listOf(999L))

        val remaining = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, remaining.size)
    }

    @Test
    fun deleteByIds_moreThan900_chunksCorrectly() = runTest {
        // Insert 950 articles to cross the Room 900-parameter chunk boundary
        val articles = (1..950).map { article(it) }
        store.upsert(articles)
        assertEquals(950, store.observePage(ArticleFilter.All, 0..999).first().size)

        // Delete all 950 in a single call — RoomArticleStore.deleteByIds chunks at 900
        val idsToDelete = (1..950L).toList()
        store.deleteByIds(idsToDelete)

        val remaining = store.observePage(ArticleFilter.All, 0..999).first()
        assertTrue(remaining.isEmpty())
    }

    // ---- observeUnreadCount ----

    @Test
    fun observeUnreadCount_allFilter_countsUnreadOnly() = runTest {
        store.upsert(listOf(
            article(1, isRead = false),
            article(2, isRead = true),
            article(3, isRead = false),
        ))

        val count = store.observeUnreadCount(ArticleFilter.All).first()
        assertEquals(2, count)
    }

    @Test
    fun observeUnreadCount_byFeedFilter() = runTest {
        store.upsert(listOf(
            article(1, feedId = 1, isRead = false),
            article(2, feedId = 1, isRead = true),
            article(3, feedId = 2, isRead = false),
            article(4, feedId = 2, isRead = false),
        ))

        val countFeed1 = store.observeUnreadCount(ArticleFilter.ByFeed(1)).first()
        assertEquals(1, countFeed1)

        val countFeed2 = store.observeUnreadCount(ArticleFilter.ByFeed(2)).first()
        assertEquals(2, countFeed2)
    }

    @Test
    fun observeUnreadCount_unreadOnlyFilter_sameAsAll() = runTest {
        store.upsert(listOf(
            article(1, isRead = false),
            article(2, isRead = true),
        ))

        val count = store.observeUnreadCount(ArticleFilter.UnreadOnly).first()
        assertEquals(1, count)
    }

    @Test
    fun observeUnreadCount_emptyStore_returnsZero() = runTest {
        val count = store.observeUnreadCount(ArticleFilter.All).first()
        assertEquals(0, count)
    }

    // ---- observePage ----

    @Test
    fun observePage_orderedByPublishedDescThenSeqDesc() = runTest {
        store.upsert(listOf(
            article(1, published = 100, seq = 10),
            article(2, published = 200, seq = 20),
            article(3, published = 200, seq = 30), // same published as 2, higher seq
            article(4, published = 300, seq = 5),
        ))

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        val ids = page.map { it.id }
        // published DESC: 300(id=4), 200(id=3,2), 100(id=1)
        // seq DESC tie-break within published=200: 30(id=3) before 20(id=2)
        assertEquals(listOf(4, 3, 2, 1), ids)
    }

    @Test
    fun observePage_nullPublishedSortsLast() = runTest {
        store.upsert(listOf(
            article(1, published = null, seq = 100),
            article(2, published = 50, seq = 1),
            article(3, published = 100, seq = 2),
        ))

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        val ids = page.map { it.id }
        // published DESC: 100(id=3), 50(id=2), NULL(id=1) at the end
        assertEquals(listOf(3, 2, 1), ids)
    }

    @Test
    fun observePage_windowReturnsCorrectSlice() = runTest {
        // Insert 10 articles with distinct published timestamps
        val articles = (1..10).map { article(it, published = it.toLong() * 100, seq = it.toLong()) }
        store.upsert(articles)

        // Window 0..2 should return the 3 newest
        val firstPage = store.observePage(ArticleFilter.All, 0..2).first()
        assertEquals(3, firstPage.size)
        assertEquals(listOf(10, 9, 8), firstPage.map { it.id })

        // Window 3..5 should return the next 3
        val secondPage = store.observePage(ArticleFilter.All, 3..5).first()
        assertEquals(3, secondPage.size)
        assertEquals(listOf(7, 6, 5), secondPage.map { it.id })

        // Window beyond data should return empty
        val beyondPage = store.observePage(ArticleFilter.All, 20..29).first()
        assertTrue(beyondPage.isEmpty())
    }

    @Test
    fun observePage_unreadFilter() = runTest {
        store.upsert(listOf(
            article(1, isRead = false, published = 300, seq = 3),
            article(2, isRead = true, published = 200, seq = 2),
            article(3, isRead = false, published = 100, seq = 1),
        ))

        val page = store.observePage(ArticleFilter.UnreadOnly, 0..99).first()
        assertEquals(2, page.size)
        assertEquals(listOf(1, 3), page.map { it.id })
    }

    @Test
    fun observePage_byFeedFilter() = runTest {
        store.upsert(listOf(
            article(1, feedId = 1, published = 300, seq = 3),
            article(2, feedId = 2, published = 200, seq = 2),
            article(3, feedId = 1, published = 100, seq = 1),
        ))

        val page = store.observePage(ArticleFilter.ByFeed(1), 0..99).first()
        assertEquals(2, page.size)
        assertEquals(listOf(1, 3), page.map { it.id })
    }

    // ---- cursor / setCursor ----

    @Test
    fun cursor_freshInstall_returnsZero() = runTest {
        assertEquals(0L, store.cursor())
    }

    @Test
    fun setCursor_thenCursor_roundTrips() = runTest {
        store.setCursor(42)
        assertEquals(42L, store.cursor())

        store.setCursor(100)
        assertEquals(100L, store.cursor())
    }

    // ---- clear ----

    @Test
    fun clear_removesAllArticlesAndResetsCursor() = runTest {
        store.upsert(listOf(article(1), article(2)))
        store.setCursor(99)

        store.clear()

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertTrue(page.isEmpty())
        assertEquals(0L, store.cursor())
    }

    @Test
    fun clear_thenInsert_works() = runTest {
        store.upsert(listOf(article(1)))
        store.setCursor(50)
        store.clear()

        // Re-insert after clear
        store.upsert(listOf(article(2)))
        store.setCursor(60)

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, page.size)
        assertEquals(2, page.single().id)
        assertEquals(60L, store.cursor())
    }
}
