package eu.monniot.feed.web.data

import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.sync.FeedMeta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * A feed store that ignores `versionchange` is worse than a missing nicety: it opens a
     * *second* connection to the same physical database [IndexedDbArticleStore] uses, so
     * even though the article store dutifully closes on `versionchange`, this one would
     * keep the database pinned. The next DB_VERSION bump would then leave the upgrading
     * tab's `open()` blocked forever — `Main.kt` never reaches `initApp()` and the user
     * gets a blank page. Mirrors
     * [IndexedDbArticleStoreTest.versionChange_closesConnectionAndFlagsStore].
     *
     * No second tab is needed: opening a second connection at `version + 1` from the same
     * page fires `versionchange` on the first. That second open's `onsuccess` only fires
     * once every other connection has closed, so awaiting it proves our handler ran — and
     * `onblocked` firing instead is exactly the deadlock this pins against.
     */
    @Test
    fun versionChange_closesConnectionAndFlagsStore() = runTest {
        val dbName = "test_feeds_versionchange_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(dbName)

        val store = IndexedDbFeedStore.open(dbName)
        assertTrue(!store.versionChangeClosed, "store starts with an open connection")

        val upgraded = suspendCancellableCoroutine<IDBDatabase> { cont ->
            val req = getIndexedDB().open(dbName, IndexedDbArticleStore.DB_VERSION + 1)
            req.onsuccess = { cont.resume(req.result.unsafeCast<IDBDatabase>()) }
            req.onerror = { cont.resumeWithException(RuntimeException("upgrade open failed: ${req.error}")) }
            req.asDynamic().onblocked = {
                cont.resumeWithException(RuntimeException("upgrade stayed blocked — the feed store's versionchange handler did not close its connection"))
            }
        }

        assertTrue(
            store.versionChangeClosed,
            "the versionchange handler must flag the store as closed once another tab upgrades",
        )

        // A subsequent operation must fail fast with a diagnosable message, not an opaque
        // InvalidStateError from db.transaction() on a closed connection.
        val error = try {
            store.observeAll().first()
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(error != null, "operations on a version-change-closed store must throw")
        assertTrue(
            (error.message ?: "").contains("reload", ignoreCase = true),
            "the failure must be diagnosable (mention reloading), got: ${error.message}",
        )

        upgraded.close()
    }

    /**
     * The real-world shape of the deadlock: in production `Main.kt` opens *both* stores
     * against `feed_articles`, so a tab holds two connections and an upgrade needs both to
     * yield. This pins the pair, not just the feed store in isolation — a regression where
     * only one of the two handlers survives still blocks every future migration, and the
     * single-store test above would not catch it.
     */
    @Test
    fun versionChange_bothStoresOnTheSameDatabaseYieldSoAnUpgradeCanProceed() = runTest {
        val dbName = "test_feeds_versionchange_pair_${Random.nextInt(0, Int.MAX_VALUE)}"
        openedDbs.add(dbName)

        val articleStore = IndexedDbArticleStore.open(dbName)
        val feedStore = IndexedDbFeedStore.open(dbName)

        val upgraded = suspendCancellableCoroutine<IDBDatabase> { cont ->
            val req = getIndexedDB().open(dbName, IndexedDbArticleStore.DB_VERSION + 1)
            req.onsuccess = { cont.resume(req.result.unsafeCast<IDBDatabase>()) }
            req.onerror = { cont.resumeWithException(RuntimeException("upgrade open failed: ${req.error}")) }
            req.asDynamic().onblocked = {
                cont.resumeWithException(RuntimeException("upgrade stayed blocked — one of the two connections to this database did not close"))
            }
        }

        assertTrue(articleStore.versionChangeClosed, "the article store must yield its connection")
        assertTrue(feedStore.versionChangeClosed, "the feed store must yield its connection")

        upgraded.close()
    }
}
