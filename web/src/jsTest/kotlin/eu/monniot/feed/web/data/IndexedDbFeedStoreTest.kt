package eu.monniot.feed.web.data

import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.sync.FeedMeta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [IndexedDbFeedStore] (BUG-63 part 1) covering the [eu.monniot.feed.shared.sync.FeedStore]
 * contract: replaceAll insert/drop/overwrite/clear semantics, deleteById, and — the
 * regression this store exists to fix — that feed metadata survives a fresh IndexedDB
 * connection to the same database without any network call re-populating it, exactly what
 * happens on a page reload while offline. Mirrors [RoomFeedStoreTest] (the Android
 * counterpart) and [IndexedDbArticleStoreTest]'s style for this file's IndexedDB store.
 *
 * Each test uses a unique database name to avoid interference between tests that may run
 * in any order within the same browser tab.
 */
class IndexedDbFeedStoreTest {

    private val openedDbs = mutableListOf<String>()

    private suspend fun createStore(): IndexedDbFeedStore {
        val name = "test_feeds_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(name)
        return IndexedDbFeedStore.open(name)
    }

    @AfterTest
    fun cleanup() {
        // Fire-and-forget, same as IndexedDbArticleStoreTest: deleteDatabase is
        // asynchronous and test teardown doesn't await promises.
        val factory = getIndexedDB()
        for (name in openedDbs) {
            factory.deleteDatabase(name)
        }
        openedDbs.clear()
    }

    // -- Helpers --

    private fun feed(
        id: Int,
        title: String?,
        customTitle: String? = null,
        url: String = "https://example.com/feed/$id",
    ) = Feed(
        id = id,
        url = url,
        title = title,
        custom_title = customTitle,
        is_paused = false,
        fetch_interval_minutes = 60,
        error_count = 0,
        last_fetched = null,
        unread_count = null,
        category_id = null,
    )

    @Test
    fun replaceAll_persistsFeedsAndResolvesCustomTitleOverTitle() = runTest {
        val store = createStore()

        store.replaceAll(listOf(feed(1, "Tech Blog"), feed(2, "News", customTitle = "My News")))

        val feeds = store.observeAll().first()
        assertEquals(2, feeds.size)
        assertEquals("Tech Blog", feeds[1]?.title)
        assertEquals("My News", feeds[2]?.customTitle)

        store.close()
    }

    @Test
    fun replaceAll_dropsFeedsMissingFromTheNewList() = runTest {
        val store = createStore()

        store.replaceAll(listOf(feed(1, "Tech Blog"), feed(2, "News")))
        store.replaceAll(listOf(feed(1, "Tech Blog"))) // feed 2 removed server-side

        val feeds = store.observeAll().first()
        assertEquals(setOf(1), feeds.keys)

        store.close()
    }

    @Test
    fun replaceAll_overwritesTitleOfAnAlreadyPersistedFeed() = runTest {
        val store = createStore()

        store.replaceAll(listOf(feed(1, "Old Name")))
        store.replaceAll(listOf(feed(1, "New Name")))

        assertEquals("New Name", store.observeAll().first()[1]?.title)

        store.close()
    }

    // A stale name used to be harmless: the cache died with the page, so the worst case was
    // a null feedTitle. Now that names are written to IndexedDB and survive a reload, a feed
    // renamed server-side that failed to overwrite its cached row would show the old name
    // offline indefinitely — so the overwrite path needs pinning in both directions.
    @Test
    fun replaceAll_clearsACustomTitleThatWasRemovedServerSide() = runTest {
        val store = createStore()

        // Rename, then un-rename: the server drops custom_title back to null and the cached
        // row must follow, or the article list keeps showing the abandoned override.
        store.replaceAll(listOf(feed(1, "Tech Blog", customTitle = "My Tech")))
        store.replaceAll(listOf(feed(1, "Tech Blog", customTitle = null)))

        val meta = store.observeAll().first()[1]
        assertNull(meta?.customTitle)
        assertEquals("Tech Blog", meta?.displayName)

        store.close()
    }

    @Test
    fun replaceAll_withEmptyList_clearsEverything() = runTest {
        val store = createStore()

        store.replaceAll(listOf(feed(1, "Tech Blog")))
        store.replaceAll(emptyList())

        val feeds = store.observeAll().first()
        assertEquals(emptyMap<Int, FeedMeta>(), feeds)

        store.close()
    }

    @Test
    fun deleteById_removesOnlyThatFeed() = runTest {
        val store = createStore()

        store.replaceAll(listOf(feed(1, "Tech Blog"), feed(2, "News")))
        store.deleteById(1)

        val feeds = store.observeAll().first()
        assertEquals(setOf(2), feeds.keys)

        store.close()
    }

    @Test
    fun deleteById_ofAnAbsentFeedIsANoOp() = runTest {
        val store = createStore()

        store.replaceAll(listOf(feed(1, "Tech Blog")))
        store.deleteById(999) // never existed

        assertEquals(setOf(1), store.observeAll().first().keys)

        store.close()
    }

    /**
     * The whole point of this store: feed metadata must be readable from a brand new
     * IndexedDB connection without any network call re-populating it — exactly what
     * happens on a browser reload while offline. Before this fix, web fell back to
     * `InMemoryFeedStore`, so every reload lost every feed name (`ArticleItem.feedTitle`
     * null, rendered as "Unknown" by the UI's `?: "Unknown"` fallback).
     */
    @Test
    fun feedNamesSurviveAFreshConnectionToTheSameDatabase() = runTest {
        val dbName = "test_feeds_persist_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(dbName)

        val store1 = IndexedDbFeedStore.open(dbName)
        store1.replaceAll(listOf(feed(1, "Tech Blog", customTitle = "My Tech")))
        store1.close()

        // Simulate a page reload: reopen the same IndexedDB.
        val store2 = IndexedDbFeedStore.open(dbName)
        val feeds = store2.observeAll().first()
        assertEquals(
            "My Tech", feeds[1]?.customTitle,
            "feed metadata must survive a fresh connection to the same IndexedDB (page reload)",
        )
        store2.close()
    }

    @Test
    fun observeAll_reflectsEachWriteOnTheNextRead() = runTest {
        // observeAll() re-queries on every version bump ([IndexedDbFeedStore._version]);
        // reading it fresh after each write is enough to pin that it isn't a snapshot
        // frozen at store-open time.
        val store = createStore()

        assertEquals(emptyMap(), store.observeAll().first(), "starts empty")

        store.replaceAll(listOf(feed(1, "Tech Blog")))
        assertEquals(setOf(1), store.observeAll().first().keys, "reflects the replaceAll")

        store.deleteById(1)
        assertEquals(emptyMap(), store.observeAll().first(), "reflects the deleteById")

        store.close()
    }
}
