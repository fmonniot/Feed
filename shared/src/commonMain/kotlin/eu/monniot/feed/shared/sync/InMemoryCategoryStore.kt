package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Non-persistent [CategoryStore] — the cache is lost on process death. Both shipping
 * clients (Android's `RoomCategoryStore`, web's `IndexedDbCategoryStore`) use a durable
 * implementation instead; this one is the [eu.monniot.feed.shared.SharedFeedRepository]
 * constructor default for tests that don't care about persistence.
 */
class InMemoryCategoryStore : CategoryStore {
    private val state = MutableStateFlow<List<Category>>(emptyList())

    override suspend fun replaceAll(categories: List<Category>) {
        state.value = categories.sortedBy { it.position }
    }

    override fun observeAll(): Flow<List<Category>> = state
}
