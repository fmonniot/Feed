package eu.monniot.feed.shared.sync

import eu.monniot.feed.shared.api.Article
import kotlinx.coroutines.flow.Flow

/**
 * Local selection filter for [ArticleStore] read operations.
 */
sealed class ArticleFilter {
    /** All articles regardless of read state or feed. */
    data object All : ArticleFilter()
    /**
     * Only unread articles, plus optionally one read article kept visible by id.
     *
     * [keepArticleId] supports the Unread view's reading flow: opening an article
     * marks it read, but it must stay in the list (at its normal sort position)
     * until the user moves on — otherwise the row vanishes mid-read and the
     * reader pane loses its backing item. It also covers reloading a deep link
     * to an already-read article. The kept article never counts toward
     * [ArticleStore.observeUnreadCount].
     */
    data class UnreadOnly(val keepArticleId: Int? = null) : ArticleFilter()
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
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * Observe a windowed page of articles matching [filter].
     *
     * [window] is a zero-based `IntRange` (e.g. `0..49` for the first 50 rows).
     * The backing query uses `LIMIT`/`OFFSET` over the `published DESC, seq DESC` order.
     *
     * **Window vs. badge contract:** The list is capped to [window].size rows and
     * contains whatever [filter] matches (read and unread articles for
     * [ArticleFilter.All]/[ArticleFilter.ByFeed]). The badge ([observeUnreadCount])
     * counts only unread articles globally. When all articles are unread,
     * `badge >= list.size`; when some are read, `badge` may be less than
     * `list.size`. True infinite-scroll paging is a future enhancement; until
     * then the UI shows at most [FeedViewModel.DEFAULT_PAGE_SIZE] rows.
     */
    fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>>

    /**
     * Observe the count of unread articles matching [filter].
     *
     * This is a `SELECT COUNT(*)` — rows are never materialized. The count
     * reflects **all** matching unread articles, not just those visible in the
     * current [observePage] window. When more than [FeedViewModel.DEFAULT_PAGE_SIZE]
     * unread articles exist, the badge will exceed the visible list length.
     */
    fun observeUnreadCount(filter: ArticleFilter): Flow<Int>

    /**
     * Observe the total count of articles matching [filter], regardless of read
     * state, uncapped by [observePage]'s window.
     *
     * Unlike [observeTotalCount] (always unfiltered), this respects [filter]:
     * [ArticleFilter.ByFeed] counts only that feed's articles, [ArticleFilter.All]
     * counts everything, and [ArticleFilter.UnreadOnly] counts unread articles
     * (matching [observeUnreadCount]'s definition — the kept read article does not
     * count). Backs the article-list header's "N total" subtitle, which must
     * reflect the true count matching the active view, not just the loaded window.
     */
    fun observeCount(filter: ArticleFilter): Flow<Int>

    /**
     * Observe the total count of articles, across all feeds, regardless of read
     * state (`SELECT COUNT(*) FROM sync_articles`, no filter applied).
     *
     * **BUG-43:** unlike [observePage] (windowed) or [observeUnreadCount] (filter-
     * scoped), this always reflects the whole local mirror. It backs the "All
     * articles" sidebar counter, which must stay stable regardless of the active
     * filter or selected feed.
     */
    fun observeTotalCount(): Flow<Int>

    /** Return the stored sync cursor (0 for a fresh install). */
    suspend fun cursor(): Long

    /** Persist the sync cursor after a successful delta application. */
    suspend fun setCursor(seq: Long)

    /**
     * Optimistically update the read state of every article in [ids] in a single
     * store transaction that notifies observers exactly **once** — regardless of
     * how many ids are supplied. Ids not present in the mirror are skipped. An
     * empty [ids] list is a no-op (no transaction, no observer notification).
     *
     * This is the primitive that keeps bulk mark-all-read from re-firing the count
     * observers once per article (the "countdown" symptom).
     */
    suspend fun markRead(ids: List<Int>, isRead: Boolean)

    /** Optimistically update the read state of a single article. */
    suspend fun markRead(id: Int, isRead: Boolean) = markRead(listOf(id), isRead)

    /**
     * Return the ids of every **unread** article matching [filter], uncapped by any
     * window.
     *
     * Backs the bulk-read fan-out (mark-all-read / mark-feed-read): the repository
     * expands a whole-view "mark read" into a per-id optimistic mutation over
     * exactly these ids, so the action flows through the same offline queue as a
     * single [markRead]. Only [ArticleFilter.All] and [ArticleFilter.ByFeed] carry a
     * distinct meaning here; [ArticleFilter.UnreadOnly] is treated as [ArticleFilter.All]
     * (its unread set is the global unread set). This is a `SELECT id` — full rows
     * are never materialized.
     */
    suspend fun unreadIds(filter: ArticleFilter): List<Int>

    /** Remove all articles belonging to a given feed. */
    suspend fun deleteByFeedId(feedId: Int)

    /** Clear all articles and reset the cursor. Used when the server signals `full_resync`. */
    suspend fun clear()

    // ---- Offline mutation queue (ticket #107 / FU-2) ----
    //
    // Persistent queue of is_read changes made locally while the server was
    // unreachable.  Each entry records the DESIRED state so that the last
    // write wins when multiple toggles happen before reconnect.  The queue
    // survives process death because it lives in the same persistent store as
    // the article mirror.

    /**
     * Persist a pending read-state change for every id in [ids], all with the same
     * desired [isRead] value, in a single store transaction.
     *
     * Calling this for an [id] already queued overwrites the earlier entry — only
     * the most-recent desired state is kept (last-write-wins, single-user). An empty
     * [ids] list is a no-op.
     */
    suspend fun enqueueMutations(ids: List<Int>, isRead: Boolean)

    /** Persist a pending read-state change for a single [id]. */
    suspend fun enqueueMutation(id: Int, isRead: Boolean) = enqueueMutations(listOf(id), isRead)

    /**
     * Remove the pending mutation for each id in [ids] after the server has
     * acknowledged them, but only for ids whose queued desired state still equals
     * [isRead] (the guard is applied per-id). Callers must pass ids that were all
     * flushed with the same [isRead] value. An empty [ids] list is a no-op.
     *
     * The value guard closes a lost-update race: if a slow PUT for `(id, true)`
     * acks *after* a newer offline `markAsUnread(id)` has overwritten the entry
     * with `(id, false)`, an unconditional delete would drop the newer, still
     * un-acked mutation — the next pull would then revert the user's change.
     * Deleting only when the stored value matches what was actually flushed
     * leaves the newer entry intact.
     */
    suspend fun dequeueMutations(ids: List<Int>, isRead: Boolean)

    /**
     * Remove the pending mutation for a single [id] if its queued value still
     * equals [isRead]. A no-op if [id] is not queued or its queued value differs.
     */
    suspend fun dequeueMutation(id: Int, isRead: Boolean) = dequeueMutations(listOf(id), isRead)

    /**
     * Return all pending mutations as a map from article id to desired `is_read`
     * state.  An empty map means no offline mutations are queued.
     */
    suspend fun pendingMutations(): Map<Int, Boolean>
}
