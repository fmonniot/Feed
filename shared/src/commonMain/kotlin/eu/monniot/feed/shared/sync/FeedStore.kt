package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Feed
import kotlinx.coroutines.flow.Flow

/**
 * The subset of [Feed] a [FeedStore] actually persists — exactly the fields needed to
 * render a feed's display name.
 *
 * Deliberately narrower than [Feed]: a store backed by this table has no honest value for
 * `is_paused`, `fetch_interval_minutes`, `error_count`, `last_fetched` or `category_id`,
 * so it must not offer them. Returning a full [Feed] with placeholders there would let a
 * future consumer (say, an offline feed-list screen) read invented state as if it were
 * real, with no compile error to stop it. Those fields are always sourced live from
 * `GET /v1/feeds`.
 */
data class FeedMeta(
    val id: Int,
    val url: String,
    val title: String?,
    val customTitle: String?,
) {
    /** The name to show for this feed: user override, then publisher title, then the url. */
    val displayName: String get() = customTitle ?: title ?: url
}

/** Projects the persisted display subset out of a server-sourced [Feed]. */
fun Feed.toFeedMeta() = FeedMeta(
    id = id,
    url = url,
    title = title,
    customTitle = custom_title,
)

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
     * `GET /v1/feeds`. Takes the server's full [Feed] and projects it down to the
     * persisted [FeedMeta] subset internally.
     */
    suspend fun replaceAll(feeds: List<Feed>)

    /**
     * Remove a single feed's cached metadata immediately, without waiting for the next
     * [replaceAll]. Used after [eu.monniot.feed.shared.FeedRepository.deleteFeed] succeeds.
     */
    suspend fun deleteById(id: Int)

    /** Observe every cached feed's persisted display metadata, keyed by id. */
    fun observeAll(): Flow<Map<Int, FeedMeta>>
}
