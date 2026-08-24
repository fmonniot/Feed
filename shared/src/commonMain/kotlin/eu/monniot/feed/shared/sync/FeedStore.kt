package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Feed
import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific persistent store for feed metadata (name, url, etc.).
 *
 * This exists solely to make feed-name resolution for [eu.monniot.feed.shared.ArticleItem]
 * durable offline: [eu.monniot.feed.shared.SharedFeedRepository.observePage] combines the
 * local article mirror with [observeAll] to fill in `feedTitle`. Feed metadata itself is
 * always sourced from the server (`GET /v1/feeds`) and mirrored here as a cache — never
 * locally mutated — so [replaceAll] is the only write most callers need.
 *
 * Android implements this with Room; other platforms may fall back to
 * [InMemoryFeedStore] until they gain their own persistent implementation.
 */
interface FeedStore {
    /**
     * Replace the entire cached feed set with [feeds] — any previously cached feed whose
     * id is absent from [feeds] is dropped. Mirrors the "full list" semantics of
     * `GET /v1/feeds`.
     */
    suspend fun replaceAll(feeds: List<Feed>)

    /**
     * Remove a single feed's cached metadata immediately, without waiting for the next
     * [replaceAll]. Used after [eu.monniot.feed.shared.FeedRepository.deleteFeed] succeeds.
     */
    suspend fun deleteById(id: Int)

    /** Observe every cached feed, keyed by id. */
    fun observeAll(): Flow<Map<Int, Feed>>
}
