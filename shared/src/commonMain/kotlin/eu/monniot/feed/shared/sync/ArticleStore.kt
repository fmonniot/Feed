package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Article
import kotlinx.coroutines.flow.Flow

/**
 * Local selection filter for [ArticleStore] read operations.
 */
sealed class ArticleFilter {
    /** All articles regardless of read state or feed. */
    data object All : ArticleFilter()
    /** Only unread articles. */
    data object UnreadOnly : ArticleFilter()
    /** Articles belonging to a specific feed. */
    data class ByFeed(val feedId: Int) : ArticleFilter()
}

/**
 * Platform-specific persistent store for the local article mirror.
 *
 * Ordering is `published DESC, seq DESC`. The `seq DESC` tie-break makes the order
 * deterministic and identical on both clients even though `published` is nullable and
 * non-monotonic (design plan E10).
 *
 * The read side is deliberately **windowed/aggregate, never whole-corpus**: the list
 * is a paged observation ([observePage]) and the badge is a SQL `COUNT` ([observeUnreadCount])
 * that never materializes rows. This keeps memory bounded even as the mirror grows to
 * 20k+ articles.
 *
 * Android implements this with Room; web with its chosen backend (IndexedDB or similar).
 */
interface ArticleStore {
    /** Insert or replace articles by `id`. Content is immutable; in practice only `is_read` changes. */
    suspend fun upsert(articles: List<Article>)

    /** Remove articles by `id` (tombstone application). */
    suspend fun deleteByIds(ids: List<Int>)

    /**
     * Observe a windowed page of articles matching [filter].
     *
     * [window] is a zero-based `IntRange` (e.g. `0..49` for the first 50 rows).
     * The backing query uses `LIMIT`/`OFFSET` over the `published DESC, seq DESC` order.
     */
    fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>>

    /**
     * Observe the count of unread articles matching [filter].
     *
     * This is a `SELECT COUNT(*)` -- rows are never materialized.
     */
    fun observeUnreadCount(filter: ArticleFilter): Flow<Int>

    /** Return the stored sync cursor (0 for a fresh install). */
    suspend fun cursor(): Long

    /** Persist the sync cursor after a successful delta application. */
    suspend fun setCursor(seq: Long)

    /** Clear all articles and reset the cursor. Used when the server signals `full_resync`. */
    suspend fun clear()
}
