package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Feed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Non-persistent [FeedStore] — the cache is lost on process death. This is the default
 * [SharedFeedRepository][eu.monniot.feed.shared.SharedFeedRepository] wiring for platforms
 * without a durable implementation yet (web) and for tests; it reproduces the repository's
 * pre-persistence behavior exactly.
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
