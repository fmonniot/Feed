package eu.monniot.feed.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.sync.FeedMeta
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [RoomFeedStore] using an in-memory Room database.
 *
 * Covers the [eu.monniot.feed.shared.sync.FeedStore] contract: replaceAll upsert/drop
 * semantics, deleteById, and — the regression this store exists to fix — that feed
 * metadata survives a fresh Room connection to the same on-disk database (simulating
 * app process death), unlike the old in-memory-only `feedsCache`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomFeedStoreTest {

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
        category_id = null,
    )

    private fun inMemoryStore(): Pair<FeedDatabase, RoomFeedStore> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return db to RoomFeedStore(db, db.feedDao())
    }

    @Test
    fun replaceAll_persistsFeedsAndResolvesCustomTitleOverTitle() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(feed(1, "Tech Blog"), feed(2, "News", customTitle = "My News")))

        val feeds = store.observeAll().first()
        assertEquals(2, feeds.size)
        assertEquals("Tech Blog", feeds[1]?.title)
        assertEquals("My News", feeds[2]?.customTitle)

        db.close()
    }

    @Test
    fun replaceAll_dropsFeedsMissingFromTheNewList() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(feed(1, "Tech Blog"), feed(2, "News")))
        store.replaceAll(listOf(feed(1, "Tech Blog"))) // feed 2 removed server-side

        val feeds = store.observeAll().first()
        assertEquals(setOf(1), feeds.keys)

        db.close()
    }

    // A stale name used to be harmless: the cache died with the process, so the worst case
    // was a null feedTitle. Now that names are written to disk and survive restarts, a feed
    // renamed server-side that failed to overwrite its cached row would show the old name
    // offline indefinitely — so the overwrite path needs pinning in both directions.

    @Test
    fun replaceAll_overwritesTitleOfAnAlreadyPersistedFeed() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(feed(1, "Old Name")))
        store.replaceAll(listOf(feed(1, "New Name")))

        assertEquals("New Name", store.observeAll().first()[1]?.title)

        db.close()
    }

    @Test
    fun replaceAll_clearsACustomTitleThatWasRemovedServerSide() = runTest {
        val (db, store) = inMemoryStore()

        // Rename, then un-rename: the server drops custom_title back to null and the cached
        // row must follow, or the article list keeps showing the abandoned override.
        store.replaceAll(listOf(feed(1, "Tech Blog", customTitle = "My Tech")))
        store.replaceAll(listOf(feed(1, "Tech Blog", customTitle = null)))

        val meta = store.observeAll().first()[1]
        assertNull(meta?.customTitle)
        assertEquals("Tech Blog", meta?.displayName)

        db.close()
    }

    @Test
    fun replaceAll_withEmptyList_clearsEverything() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(feed(1, "Tech Blog")))
        store.replaceAll(emptyList())

        val feeds = store.observeAll().first()
        assertEquals(emptyMap<Int, FeedMeta>(), feeds)

        db.close()
    }

    @Test
    fun replaceAll_handlesMoreFeedsThanTheSqliteVariableLimit() = runTest {
        val (db, store) = inMemoryStore()

        // The original `DELETE FROM feeds WHERE id NOT IN (:ids)` prune bound one SQL variable
        // per feed id, and Room does not chunk `IN (:list)`. On a real device below API 32 that
        // threw "too many SQL variables" past SQLITE_MAX_VARIABLE_NUMBER = 999 — on every
        // feed-list load and refresh(), not just once. Robolectric's SQLite raises the limit to
        // 32766, so the count here is sized to trip that rather than the device-realistic 999:
        // this asserts the prune-free implementation, and it does fail against the old one.
        val many = (1..33000).map { feed(it, "Feed $it") }
        store.replaceAll(many)
        store.replaceAll(many) // second pass is the one that used to prune (and throw)

        val feeds = store.observeAll().first()
        assertEquals(33000, feeds.size)
        assertEquals("Feed 33000", feeds[33000]?.title)

        db.close()
    }

    @Test
    fun deleteById_removesOnlyThatFeed() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(feed(1, "Tech Blog"), feed(2, "News")))
        store.deleteById(1)

        val feeds = store.observeAll().first()
        assertEquals(setOf(2), feeds.keys)

        db.close()
    }

    @Test
    fun feedNamesSurviveAFreshConnectionToTheSameDatabase() = runTest {
        // Regression test for the offline "Unknown" feed-name bug (see ArticleRow.kt's
        // `article.feedTitle ?: "Unknown"` fallback): feed metadata must be readable
        // from a brand new Room connection without any network call re-populating it —
        // exactly what happens on an app cold start while offline.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.cacheDir, "feed_name_persistence_test.db")
        dbFile.delete()

        try {
            val db1 = Room.databaseBuilder(context, FeedDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()
            RoomFeedStore(db1, db1.feedDao())
                .replaceAll(listOf(feed(1, "Tech Blog", customTitle = "My Tech")))
            db1.close()

            // A fresh connection to the same file — simulates the process restart that
            // the old in-memory-only feedsCache could not survive.
            val db2 = Room.databaseBuilder(context, FeedDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()
            val restoredStore = RoomFeedStore(db2, db2.feedDao())

            val feeds = restoredStore.observeAll().first()
            assertEquals("My Tech", feeds[1]?.customTitle)

            db2.close()
        } finally {
            dbFile.delete()
        }
    }
}
