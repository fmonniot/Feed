package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Feed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Non-persistent [FeedStore] — the cache is lost on process death. Both shipping clients
 * (Android's `RoomFeedStore`, web's `IndexedDbFeedStore`) use a durable implementation
 * instead; this one exists for tests that don't care about persistence and need to pass
 * [eu.monniot.feed.shared.SharedFeedRepository]'s required `feedStore` argument explicitly.
 */
class InMemoryFeedStore : FeedStore {
    private val state = MutableStateFlow<Map<Int, FeedMeta>>(emptyMap())

    override suspend fun replaceAll(feeds: List<Feed>) {
        state.value = feeds.associate { it.id to it.toFeedMeta() }
    }

    override suspend fun deleteById(id: Int) {
        state.update { it - id }
    }

    override fun observeAll(): Flow<Map<Int, FeedMeta>> = state
}
