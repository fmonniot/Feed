package eu.monniot.feed.web.data

import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.sync.ArticleFilter
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [IndexedDbArticleStore] covering the full [ArticleStore] contract surface:
 * upsert-by-id, deleteByIds, windowed observePage (ordered published DESC, seq DESC),
 * observeUnreadCount aggregate, cursor round-trip + persistence across simulated reload,
 * and clear().
 *
 * Each test uses a unique database name to avoid interference between tests that may
 * run in any order within the same browser tab.
 */
class IndexedDbArticleStoreTest {

    private val openedDbs = mutableListOf<String>()

    private suspend fun createStore(): IndexedDbArticleStore {
        val name = "test_articles_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(name)
        return IndexedDbArticleStore.open(name)
    }

    @AfterTest
    fun cleanup() {
        // Delete test databases. We fire-and-forget here as deleteDatabase
        // is asynchronous and test teardown doesn't await promises.
        val factory = getIndexedDB()
        for (name in openedDbs) {
            factory.deleteDatabase(name)
        }
        openedDbs.clear()
    }

    // -- Helpers --

    private fun article(
        id: Int,
        feedId: Int = 1,
        published: Long? = 1000L,
        seq: Long = id.toLong(),
        isRead: Boolean = false,
        title: String? = "Article $id",
    ): Article = Article(
        id = id,
        feed_id = feedId,
        guid = "guid-$id",
        title = title,
        content = "Content for $id",
        link = "https://example.com/$id",
        author = "Author",
        published = published,
        is_read = isRead,
        fetched_at = 500L,
        seq = seq,
    )

    // -----------------------------------------------------------------------
    // Upsert
    // -----------------------------------------------------------------------

    @Test
    fun upsertInsertsNewArticles() = runTest {
        val store = createStore()
        val articles = listOf(article(1), article(2), article(3))
        store.upsert(articles)

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(3, page.size)
        assertTrue(page.any { it.id == 1 })
        assertTrue(page.any { it.id == 2 })
        assertTrue(page.any { it.id == 3 })
        store.close()
    }

    @Test
    fun upsertReplacesExistingById() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1, isRead = false)))

        // Replace: same id, different is_read
        store.upsert(listOf(article(1, isRead = true)))

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, page.size)
        assertEquals(true, page[0].is_read)
        store.close()
    }

    @Test
    fun upsertEmptyListIsNoOp() = runTest {
        val store = createStore()
        store.upsert(emptyList())
        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(0, page.size)
        store.close()
    }

    // -----------------------------------------------------------------------
    // DeleteByIds
    // -----------------------------------------------------------------------

    @Test
    fun deleteByIdsRemovesArticles() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1), article(2), article(3)))
        store.deleteByIds(listOf(1L, 3L))

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, page.size)
        assertEquals(2, page[0].id)
        store.close()
    }

    @Test
    fun deleteByIdsWithNonExistentIdIsNoOp() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1)))
        store.deleteByIds(listOf(999L))

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, page.size)
        store.close()
    }

    @Test
    fun deleteByIdsEmptyListIsNoOp() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1)))
        store.deleteByIds(emptyList())

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, page.size)
        store.close()
    }

    // -----------------------------------------------------------------------
    // markRead
    // -----------------------------------------------------------------------

    @Test
    fun markReadTogglesReadState() = runTest {
        val store = createStore()
        store.upsert(listOf(
            article(1, isRead = false),
            article(2, isRead = false),
        ))
        assertEquals(2, store.observeUnreadCount(ArticleFilter.All).first())

        // Mark article 1 as read
        store.markRead(1, true)

        val page1 = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(true, page1.first { it.id == 1 }.is_read)
        assertEquals(false, page1.first { it.id == 2 }.is_read)
        assertEquals(1, store.observeUnreadCount(ArticleFilter.All).first())

        // Toggle back to unread
        store.markRead(1, false)

        val page2 = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(false, page2.first { it.id == 1 }.is_read)
        assertEquals(2, store.observeUnreadCount(ArticleFilter.All).first())
        store.close()
    }

    @Test
    fun markReadNonExistentIdIsNoOp() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1, isRead = false)))

        // markRead on a non-existent id should not throw
        store.markRead(999, true)

        // Original article unchanged
        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, page.size)
        assertEquals(false, page[0].is_read)
        store.close()
    }

    // -----------------------------------------------------------------------
    // deleteByFeedId
    // -----------------------------------------------------------------------

    @Test
    fun deleteByFeedIdRemovesOnlyThatFeed() = runTest {
        val store = createStore()
        store.upsert(listOf(
            article(1, feedId = 10, isRead = false),
            article(2, feedId = 10, isRead = true),
            article(3, feedId = 20, isRead = false),
            article(4, feedId = 30, isRead = false),
        ))
        assertEquals(4, store.observePage(ArticleFilter.All, 0..99).first().size)

        // Delete feed 10
        store.deleteByFeedId(10)

        val remaining = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(2, remaining.size)
        assertEquals(setOf(3, 4), remaining.map { it.id }.toSet())

        // Unread badge reflects the deletion
        assertEquals(2, store.observeUnreadCount(ArticleFilter.All).first())
        store.close()
    }

    @Test
    fun deleteByFeedIdNonExistentFeedIsNoOp() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1, feedId = 10)))

        store.deleteByFeedId(999)

        val remaining = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(1, remaining.size)
        store.close()
    }

    // -----------------------------------------------------------------------
    // observePage — ordering: published DESC, seq DESC, nulls last
    // -----------------------------------------------------------------------

    @Test
    fun observePageOrdersPublishedDescSeqDesc() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, published = 1000, seq = 10),
                article(2, published = 2000, seq = 20),
                article(3, published = 2000, seq = 30),
                article(4, published = 3000, seq = 40),
            )
        )

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        val ids = page.map { it.id }
        // Expected: published 3000 first, then 2000 (seq 30 before 20), then 1000
        assertEquals(listOf(4, 3, 2, 1), ids)
        store.close()
    }

    @Test
    fun observePageNullPublishedSortsLast() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, published = null, seq = 100),
                article(2, published = 500, seq = 50),
                article(3, published = null, seq = 200),
            )
        )

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        val ids = page.map { it.id }
        // Non-null published first (only id=2), then null published by seq DESC (id=3, id=1)
        assertEquals(listOf(2, 3, 1), ids)
        store.close()
    }

    // -----------------------------------------------------------------------
    // observePage — windowing (offset/limit)
    // -----------------------------------------------------------------------

    @Test
    fun observePageRespectsWindow() = runTest {
        val store = createStore()
        // Insert 5 articles with distinct published times for clear ordering
        store.upsert(
            (1..5).map { i -> article(i, published = (i * 1000).toLong(), seq = i.toLong()) }
        )

        // Window 1..2 should get 2 articles, skipping the first
        val page = store.observePage(ArticleFilter.All, 1..2).first()
        assertEquals(2, page.size)
        // Order is published DESC: 5, 4, 3, 2, 1
        // Skip 1, take 2 => ids 4, 3
        assertEquals(listOf(4, 3), page.map { it.id })
        store.close()
    }

    @Test
    fun observePageEmptyWindowReturnsEmpty() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1)))

        // Window past available data
        val page = store.observePage(ArticleFilter.All, 10..19).first()
        assertEquals(0, page.size)
        store.close()
    }

    // -----------------------------------------------------------------------
    // observePage — filters
    // -----------------------------------------------------------------------

    @Test
    fun observePageFilterUnreadOnly() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, isRead = false, published = 3000, seq = 30),
                article(2, isRead = true, published = 2000, seq = 20),
                article(3, isRead = false, published = 1000, seq = 10),
            )
        )

        val page = store.observePage(ArticleFilter.UnreadOnly(), 0..99).first()
        assertEquals(2, page.size)
        assertEquals(listOf(1, 3), page.map { it.id })
        store.close()
    }

    @Test
    fun observePageFilterByFeed() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, feedId = 10, published = 3000, seq = 30),
                article(2, feedId = 20, published = 2000, seq = 20),
                article(3, feedId = 10, published = 1000, seq = 10),
            )
        )

        val page = store.observePage(ArticleFilter.ByFeed(10), 0..99).first()
        assertEquals(2, page.size)
        assertEquals(listOf(1, 3), page.map { it.id })
        store.close()
    }

    @Test
    fun observePageFilterWithOffset() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, feedId = 10, published = 5000, seq = 50),
                article(2, feedId = 20, published = 4000, seq = 40),
                article(3, feedId = 10, published = 3000, seq = 30),
                article(4, feedId = 20, published = 2000, seq = 20),
                article(5, feedId = 10, published = 1000, seq = 10),
            )
        )
        // ByFeed(10) matches ids 1, 3, 5 in desc published order.
        // Window 1..1 should skip id=1, return id=3
        val page = store.observePage(ArticleFilter.ByFeed(10), 1..1).first()
        assertEquals(1, page.size)
        assertEquals(3, page[0].id)
        store.close()
    }

    @Test
    fun observePageUnreadFilterWithOffset() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, isRead = false, published = 5000, seq = 50),
                article(2, isRead = true, published = 4000, seq = 40),
                article(3, isRead = false, published = 3000, seq = 30),
                article(4, isRead = false, published = 2000, seq = 20),
            )
        )
        // UnreadOnly matches ids 1, 3, 4 in desc published order.
        // Window 1..2 should skip id=1, return ids 3, 4
        val page = store.observePage(ArticleFilter.UnreadOnly(), 1..2).first()
        assertEquals(2, page.size)
        assertEquals(listOf(3, 4), page.map { it.id })
        store.close()
    }

    @Test
    fun observePageUnreadFilterKeepsReadArticleAtSortPosition() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, isRead = false, published = 3000, seq = 30),
                article(2, isRead = true, published = 2000, seq = 20),
                article(3, isRead = false, published = 1000, seq = 10),
                article(4, isRead = true, published = 500, seq = 5),
            )
        )

        // keepArticleId=2 keeps the read article between the unread ones;
        // the other read article (4) stays excluded.
        val page = store.observePage(ArticleFilter.UnreadOnly(keepArticleId = 2), 0..99).first()
        assertEquals(listOf(1, 2, 3), page.map { it.id })
        store.close()
    }

    @Test
    fun observePageUnreadFilterKeepIdAppliesWithinWindow() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, isRead = false, published = 5000, seq = 50),
                article(2, isRead = true, published = 4000, seq = 40),
                article(3, isRead = false, published = 3000, seq = 30),
                article(4, isRead = false, published = 2000, seq = 20),
            )
        )

        // Matching ids in order: 1, 2 (kept), 3, 4. Window 1..2 => ids 2, 3.
        val page = store.observePage(ArticleFilter.UnreadOnly(keepArticleId = 2), 1..2).first()
        assertEquals(listOf(2, 3), page.map { it.id })
        store.close()
    }

    // -----------------------------------------------------------------------
    // observeUnreadCount — aggregate
    // -----------------------------------------------------------------------

    @Test
    fun observeUnreadCountAll() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, isRead = false),
                article(2, isRead = true),
                article(3, isRead = false),
            )
        )

        val count = store.observeUnreadCount(ArticleFilter.All).first()
        assertEquals(2, count)
        store.close()
    }

    @Test
    fun observeUnreadCountUnreadOnly() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, isRead = false),
                article(2, isRead = true),
                article(3, isRead = false),
            )
        )

        // UnreadOnly filter: count of unread articles that pass the filter (which is all unread)
        val count = store.observeUnreadCount(ArticleFilter.UnreadOnly()).first()
        assertEquals(2, count)
        store.close()
    }

    @Test
    fun observeUnreadCountExcludesKeptReadArticle() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, isRead = false),
                article(2, isRead = true),
                article(3, isRead = false),
            )
        )

        // The kept read article is visible in the page but never counted.
        val count = store.observeUnreadCount(ArticleFilter.UnreadOnly(keepArticleId = 2)).first()
        assertEquals(2, count)
        store.close()
    }

    @Test
    fun observeUnreadCountByFeed() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, feedId = 10, isRead = false),
                article(2, feedId = 10, isRead = true),
                article(3, feedId = 20, isRead = false),
                article(4, feedId = 10, isRead = false),
            )
        )

        val count = store.observeUnreadCount(ArticleFilter.ByFeed(10)).first()
        assertEquals(2, count)
        store.close()
    }

    @Test
    fun observeUnreadCountEmptyStore() = runTest {
        val store = createStore()
        val count = store.observeUnreadCount(ArticleFilter.All).first()
        assertEquals(0, count)
        store.close()
    }

    @Test
    fun observeUnreadCountUpdatesAfterUpsert() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1, isRead = false)))

        assertEquals(1, store.observeUnreadCount(ArticleFilter.All).first())

        // Mark as read via upsert
        store.upsert(listOf(article(1, isRead = true)))
        assertEquals(0, store.observeUnreadCount(ArticleFilter.All).first())
        store.close()
    }

    // -----------------------------------------------------------------------
    // observeTotalCount — unfiltered aggregate (BUG-43)
    // -----------------------------------------------------------------------

    @Test
    fun observeTotalCountIsUnfilteredAcrossFeedsAndReadState() = runTest {
        val store = createStore()
        store.upsert(
            listOf(
                article(1, feedId = 10, isRead = false),
                article(2, feedId = 10, isRead = true),
                article(3, feedId = 20, isRead = false),
            )
        )

        val count = store.observeTotalCount().first()
        assertEquals(3, count)
        store.close()
    }

    @Test
    fun observeTotalCountEmptyStore() = runTest {
        val store = createStore()
        val count = store.observeTotalCount().first()
        assertEquals(0, count)
        store.close()
    }

    @Test
    fun observeTotalCountUpdatesAfterUpsertAndDelete() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1), article(2)))
        assertEquals(2, store.observeTotalCount().first())

        store.upsert(listOf(article(3)))
        assertEquals(3, store.observeTotalCount().first())

        store.deleteByIds(listOf(1L))
        assertEquals(2, store.observeTotalCount().first())
        store.close()
    }

    // -----------------------------------------------------------------------
    // Cursor persistence
    // -----------------------------------------------------------------------

    @Test
    fun cursorDefaultsToZero() = runTest {
        val store = createStore()
        assertEquals(0L, store.cursor())
        store.close()
    }

    @Test
    fun setCursorAndReadBack() = runTest {
        val store = createStore()
        store.setCursor(42L)
        assertEquals(42L, store.cursor())
        store.close()
    }

    @Test
    fun cursorPersistsAcrossReopen() = runTest {
        // Use a fixed db name so we can "reopen" the same DB
        val dbName = "test_cursor_persist_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(dbName)

        val store1 = IndexedDbArticleStore.open(dbName)
        store1.setCursor(99L)
        store1.close()

        // Simulate a page reload by reopening the same database
        val store2 = IndexedDbArticleStore.open(dbName)
        assertEquals(99L, store2.cursor())
        store2.close()
    }

    // -----------------------------------------------------------------------
    // Persistence across simulated reload
    // -----------------------------------------------------------------------

    @Test
    fun articlesSurviveReopen() = runTest {
        val dbName = "test_persist_articles_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(dbName)

        val store1 = IndexedDbArticleStore.open(dbName)
        store1.upsert(
            listOf(
                article(1, published = 2000, seq = 20),
                article(2, published = 1000, seq = 10),
            )
        )
        store1.close()

        // Reopen: articles must still be there
        val store2 = IndexedDbArticleStore.open(dbName)
        val page = store2.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(2, page.size)
        assertEquals(listOf(1, 2), page.map { it.id })
        store2.close()
    }

    // -----------------------------------------------------------------------
    // clear()
    // -----------------------------------------------------------------------

    @Test
    fun clearRemovesAllArticlesAndCursor() = runTest {
        val store = createStore()
        store.upsert(listOf(article(1), article(2)))
        store.setCursor(50L)

        store.clear()

        val page = store.observePage(ArticleFilter.All, 0..99).first()
        assertEquals(0, page.size)
        assertEquals(0L, store.cursor())
        store.close()
    }

    // -----------------------------------------------------------------------
    // Article field round-trip
    // -----------------------------------------------------------------------

    @Test
    fun allArticleFieldsRoundTrip() = runTest {
        val store = createStore()
        val original = Article(
            id = 42,
            feed_id = 7,
            guid = "unique-guid",
            title = "Test Title",
            content = "<p>Some HTML</p>",
            link = "https://example.com/article",
            author = "Jane Doe",
            published = 1719500000L,
            is_read = true,
            fetched_at = 1719400000L,
            link_status = 200,
            link_checked_at = 1719450000L,
            seq = 99L,
        )
        store.upsert(listOf(original))

        val retrieved = store.observePage(ArticleFilter.All, 0..0).first().single()
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
        store.close()
    }

    @Test
    fun nullableFieldsRoundTrip() = runTest {
        val store = createStore()
        val original = Article(
            id = 1,
            feed_id = 1,
            guid = "g",
            title = null,
            content = null,
            link = null,
            author = null,
            published = null,
            is_read = false,
            fetched_at = null,
            link_status = null,
            link_checked_at = null,
            seq = 5L,
        )
        store.upsert(listOf(original))

        val retrieved = store.observePage(ArticleFilter.All, 0..0).first().single()
        assertEquals(null, retrieved.title)
        assertEquals(null, retrieved.content)
        assertEquals(null, retrieved.link)
        assertEquals(null, retrieved.author)
        assertEquals(null, retrieved.published)
        assertEquals(null, retrieved.fetched_at)
        assertEquals(null, retrieved.link_status)
        assertEquals(null, retrieved.link_checked_at)
        store.close()
    }

    // -----------------------------------------------------------------------
    // Regression: stuck-"Syncing…" transaction completion-handler race
    // -----------------------------------------------------------------------

    /**
     * An IndexedDB transaction auto-commits — firing `oncomplete` — as soon as it
     * goes idle and control returns to the event loop, which happens *during* any
     * `await` inside the transaction block. The original [IndexedDbArticleStore.withTransaction]
     * registered `oncomplete` only *after* the block returned, so a transaction
     * that committed mid-block fired `oncomplete` into the void and the caller
     * suspended forever. In the real app the first victim was the single-`get`
     * readonly `cursor()` read at the top of `SyncEngine.sync()`: it hung while
     * holding the sync mutex, so every refresh wedged and the sidebar sat on
     * "Syncing…" permanently.
     *
     * This runs on the real dispatcher (via [GlobalScope.promise], not `runTest`'s
     * virtual scheduler, which linearizes dispatches and hides the race) and forces
     * the failing interleaving deterministically: the block awaits a `get`, then
     * yields a real macrotask ([delay]) during which the idle readonly transaction
     * commits. On the buggy ordering `withTransaction` never observes completion and
     * this times out to `null`; with the fix it returns normally.
     */
    @Test
    fun withTransactionObservesCompletionWhenTxCommitsMidBlock() = GlobalScope.promise {
        val store = createStore()
        val result = withTimeoutOrNull(4000) {
            store.withTransaction(arrayOf(IndexedDbArticleStore.STORE_META), "readonly") { tx ->
                val s = tx.objectStore(IndexedDbArticleStore.STORE_META)
                // Mirror cursor(): await a single get, leaving the tx idle.
                suspendCancellableCoroutine<dynamic> { cont ->
                    val req = s.get("syncCursor")
                    req.onsuccess = { cont.resume(req.result) }
                    req.onerror = { cont.resumeWithException(RuntimeException("get failed")) }
                }
                // Hand control back to the event loop so the idle readonly tx
                // commits before the old code would have attached its handler.
                delay(50)
                "done"
            }
        }
        assertEquals(
            "done",
            result,
            "withTransaction must observe oncomplete even when the tx commits during block (regression: stuck 'Syncing…')",
        )
        store.close()
    }
}
