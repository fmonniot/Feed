package eu.monniot.feed.store

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.sync.CategoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [CategoryStore] (BUG-63 part 2).
 *
 * Maps between the shared [Category] model and [CategoryEntity], delegates all persistence
 * to [CategoryDao]. Lets the offline Feeds screen group feeds into folders — before this,
 * [eu.monniot.feed.shared.FeedViewModel.categories] was populated solely by a successful
 * `getCategories()` network call and stayed empty across a cold start with no connectivity.
 */
class RoomCategoryStore(private val db: RoomDatabase, private val dao: CategoryDao) : CategoryStore {

    /**
     * Clear-then-insert in one transaction — same rationale as [RoomFeedStore.replaceAll]:
     * commits atomically so [observeAll] never sees the empty intermediate state, and avoids
     * an `IN (:list)` prune's per-id SQL variable cost (category counts are small in
     * practice, but there's no reason to reintroduce the pattern BUG-62 moved away from).
     */
    override suspend fun replaceAll(categories: List<Category>) {
        db.withTransaction {
            dao.clear()
            if (categories.isNotEmpty()) dao.upsert(categories.map { it.toEntity() })
        }
    }

    override fun observeAll(): Flow<List<Category>> =
        dao.observeAll().map { entities -> entities.map { it.toCategory() } }
}

// ---- Mapping helpers ----

private fun Category.toEntity() = CategoryEntity(
    id = id,
    name = name,
    position = position,
)

private fun CategoryEntity.toCategory() = Category(
    id = id,
    name = name,
    position = position,
)
