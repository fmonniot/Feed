package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Feed
import kotlinx.coroutines.flow.Flow

/**
 * The subset of [Feed] a [FeedStore] actually persists.
 *
 * BUG-62 (part 1) persisted only the four display fields (id/url/title/customTitle) needed
 * to resolve a feed's name. BUG-63 part 2 widened this to also cover what an offline sidebar
 * needs to group feeds into folders and show a (necessarily point-in-time) health indicator:
 * [categoryId], [isPaused], [errorCount], [serverFeedStatus], [severity].
 *
 * Still deliberately narrower than [Feed]: a store backed by this table has no honest value
 * for `fetch_interval_minutes`, `last_fetched`, `first_410_at`, `last_error_kind`,
 * `last_http_status`, `consecutive_failure_count`, `retries_paused`, `next_retry_at` or
 * `position`, so it must not offer them. Returning a full [Feed] with placeholders there
 * would let a future consumer read invented state as if it were real, with no compile error
 * to stop it. Those fields are always sourced live from `GET /v1/feeds`.
 *
 * Consumers must also not present [isPaused]/[errorCount]/[serverFeedStatus]/[severity] from
 * a cached [FeedMeta] as *live* — they are a snapshot from whenever the cache was last
 * written, not a guarantee about the feed's current state. See
 * [eu.monniot.feed.shared.FeedUiItem.stale].
 */
data class FeedMeta(
    val id: Int,
    val url: String,
    val title: String?,
    val customTitle: String?,
    /** Category id from the server (null = uncategorized) — needed for offline folder grouping. */
    val categoryId: Int?,
    val isPaused: Boolean,
    val errorCount: Int,
    /** Server-authoritative status string ("ok" / "error" / "parse_error" / "dead"). Null = older server. */
    val serverFeedStatus: String?,
    /** Severity from #81: "error" or "warn". Null = healthy feed. */
    val severity: String?,
) {
    /** The name to show for this feed: user override, then publisher title, then the url. */
    val displayName: String get() = customTitle ?: title ?: url
}

/** Projects the persisted subset out of a server-sourced [Feed]. */
fun Feed.toFeedMeta() = FeedMeta(
    id = id,
    url = url,
    title = title,
    customTitle = custom_title,
    categoryId = category_id,
    isPaused = is_paused,
    errorCount = error_count,
    serverFeedStatus = feed_status,
    severity = severity,
)

/**
 * Platform-specific persistent store for feed metadata (name, url, category, health, etc.).
 *
 * Originally existed solely to make feed-name resolution for
 * [eu.monniot.feed.shared.ArticleItem] durable offline (BUG-62):
 * [eu.monniot.feed.shared.SharedFeedRepository.observePage] combines the local article
 * mirror with [observeAll] to fill in `feedTitle`. BUG-63 part 2 additionally uses
 * [observeAll] to seed [eu.monniot.feed.shared.FeedViewModel]'s feed list before any
 * network call succeeds, so the sidebar/subscriptions screen can group feeds into folders
 * offline. Feed metadata itself is always sourced from the server (`GET /v1/feeds`) and
 * mirrored here as a cache — never locally mutated — so [replaceAll] is the only write most
 * callers need.
 *
 * Android implements this with Room (`RoomFeedStore`) and web with IndexedDB
 * (`IndexedDbFeedStore`), so both platforms persist feed metadata across process death /
 * page reload. [InMemoryFeedStore] remains available as a non-persistent implementation for
 * tests that don't care about persistence.
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
