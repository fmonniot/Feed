package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Category
import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific persistent store for the category list (BUG-63 part 2).
 *
 * Mirrors [FeedStore]'s shape and purpose, one level up: [Category] (id/name/position) has
 * no server-live fields the way [eu.monniot.feed.shared.api.Feed] does, so there is no
 * narrower projection to define here — the whole model is safe to cache and replay offline.
 * This exists so [eu.monniot.feed.shared.FeedViewModel]'s category list can be seeded before
 * any successful `GET /v1/categories` call, letting the sidebar/subscriptions screen group
 * feeds into folders while offline instead of only ever showing a flat list.
 *
 * Android implements this with Room (`RoomCategoryStore`) and web with IndexedDB
 * (`IndexedDbCategoryStore`). [InMemoryCategoryStore] is the non-persistent default for
 * tests and any [eu.monniot.feed.shared.SharedFeedRepository] caller that doesn't need
 * persistence.
 */
interface CategoryStore {
    /**
     * Replace the entire cached category set with [categories] — any previously cached
     * category whose id is absent from [categories] is dropped. Mirrors the "full list"
     * semantics of `GET /v1/categories`.
     */
    suspend fun replaceAll(categories: List<Category>)

    /** Observe every cached category, ordered by [Category.position] (matches the server). */
    fun observeAll(): Flow<List<Category>>
}
