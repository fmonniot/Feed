package eu.monniot.feed.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import eu.monniot.feed.shared.api.Category
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [RoomCategoryStore] using an in-memory Room database (BUG-63 part 2).
 *
 * Covers the [eu.monniot.feed.shared.sync.CategoryStore] contract: replaceAll upsert/drop
 * semantics, position ordering, and — the regression this store exists to fix — that the
 * category list survives a fresh Room connection to the same on-disk database, letting the
 * offline Feeds screen group feeds into folders instead of only ever rendering a flat list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomCategoryStoreTest {

    private fun inMemoryStore(): Pair<FeedDatabase, RoomCategoryStore> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return db to RoomCategoryStore(db, db.categoryDao())
    }

    @Test
    fun replaceAll_persistsCategoriesOrderedByPosition() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(
            Category(id = 1, name = "Tech", position = 1),
            Category(id = 2, name = "Craft", position = 0),
        ))

        val categories = store.observeAll().first()
        assertEquals("must be ordered by position, not insertion order", listOf("Craft", "Tech"), categories.map { it.name })

        db.close()
    }

    @Test
    fun replaceAll_dropsCategoriesMissingFromTheNewList() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(Category(id = 1, name = "Tech", position = 0), Category(id = 2, name = "Craft", position = 1)))
        store.replaceAll(listOf(Category(id = 1, name = "Tech", position = 0))) // category 2 removed server-side

        val categories = store.observeAll().first()
        assertEquals(setOf(1), categories.map { it.id }.toSet())

        db.close()
    }

    @Test
    fun replaceAll_overwritesNameOfAnAlreadyPersistedCategory() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(Category(id = 1, name = "Old Name", position = 0)))
        store.replaceAll(listOf(Category(id = 1, name = "New Name", position = 0)))

        assertEquals("New Name", store.observeAll().first().first { it.id == 1 }.name)

        db.close()
    }

    @Test
    fun replaceAll_withEmptyList_clearsEverything() = runTest {
        val (db, store) = inMemoryStore()

        store.replaceAll(listOf(Category(id = 1, name = "Tech", position = 0)))
        store.replaceAll(emptyList())

        assertEquals(emptyList<Category>(), store.observeAll().first())

        db.close()
    }

    @Test
    fun categoriesSurviveAFreshConnectionToTheSameDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = java.io.File(context.cacheDir, "category_persistence_test.db")
        dbFile.delete()

        try {
            val db1 = Room.databaseBuilder(context, FeedDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()
            RoomCategoryStore(db1, db1.categoryDao())
                .replaceAll(listOf(Category(id = 1, name = "Tech", position = 0)))
            db1.close()

            // A fresh connection to the same file — simulates the process restart that a
            // non-persistent (in-memory) category cache could not survive.
            val db2 = Room.databaseBuilder(context, FeedDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()
            val restoredStore = RoomCategoryStore(db2, db2.categoryDao())

            val categories = restoredStore.observeAll().first()
            assertEquals("Tech", categories.first { it.id == 1 }.name)

            db2.close()
        } finally {
            dbFile.delete()
        }
    }
}
