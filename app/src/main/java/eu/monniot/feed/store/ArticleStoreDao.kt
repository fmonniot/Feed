package eu.monniot.feed.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO backing the [eu.monniot.feed.shared.sync.ArticleStore] contract.
 *
 * All read-side methods are windowed or aggregate — no "load every row" path.
 * Ordering is `published DESC NULLS LAST, seq DESC`.
 */
@Dao
interface ArticleStoreDao {

    // ---- Write side ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(articles: List<SyncArticleEntity>)

    @Query("DELETE FROM sync_articles WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * Update read state for a set of ids in one statement — one InvalidationTracker
     * tick for the whole batch (callers chunk at the SQLite host-param limit).
     */
    @Query("UPDATE sync_articles SET is_read = :isRead WHERE id IN (:ids)")
    suspend fun markRead(ids: List<Int>, isRead: Boolean)

    @Query("DELETE FROM sync_articles WHERE feed_id = :feedId")
    suspend fun deleteByFeedId(feedId: Int)

    @Query("DELETE FROM sync_articles")
    suspend fun clearArticles()

    // ---- Read side: paged observations ----

    /**
     * All articles, ordered `sort_published DESC, seq DESC`, windowed.
     * `sort_published` is `COALESCE(published, 0)`, so NULL published values
     * sort last (0 < any real epoch-seconds timestamp) and the order is
     * index-satisfiable via `index_sync_articles_sort_published_seq`.
     */
    @Query("""
        SELECT * FROM sync_articles
        ORDER BY sort_published DESC,
                 seq DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observePageAll(limit: Int, offset: Int): Flow<List<SyncArticleEntity>>

    /**
     * Unread articles only, same ordering. [keepArticleId] additionally keeps one
     * read article visible (`id = NULL` matches no row, so a null keep id means
     * strictly unread).
     * Note: the `(sort_published, seq)` index only covers the unfiltered path;
     * this query still requires a temp sort due to the `WHERE is_read = 0` filter.
     */
    @Query("""
        SELECT * FROM sync_articles
        WHERE is_read = 0 OR id = :keepArticleId
        ORDER BY sort_published DESC,
                 seq DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observePageUnread(keepArticleId: Int?, limit: Int, offset: Int): Flow<List<SyncArticleEntity>>

    /**
     * Articles for a specific feed, same ordering.
     * Note: the `(sort_published, seq)` index only covers the unfiltered path;
     * this query still requires a temp sort due to the `WHERE feed_id` filter.
     */
    @Query("""
        SELECT * FROM sync_articles
        WHERE feed_id = :feedId
        ORDER BY sort_published DESC,
                 seq DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observePageByFeed(feedId: Int, limit: Int, offset: Int): Flow<List<SyncArticleEntity>>

    // ---- Read side: unread id sets (bulk-read fan-out) ----

    /** Ids of all unread articles, uncapped. Backs mark-all-read fan-out. */
    @Query("SELECT id FROM sync_articles WHERE is_read = 0")
    suspend fun unreadIdsAll(): List<Int>

    /** Ids of all unread articles in one feed, uncapped. Backs mark-feed-read fan-out. */
    @Query("SELECT id FROM sync_articles WHERE is_read = 0 AND feed_id = :feedId")
    suspend fun unreadIdsByFeed(feedId: Int): List<Int>

    // ---- Read side: aggregate counts ----

    @Query("SELECT COUNT(*) FROM sync_articles WHERE is_read = 0")
    fun observeUnreadCountAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_articles WHERE is_read = 0 AND feed_id = :feedId")
    fun observeUnreadCountByFeed(feedId: Int): Flow<Int>

    /** BUG-43: total article count across all feeds, regardless of read state. */
    @Query("SELECT COUNT(*) FROM sync_articles")
    fun observeTotalCount(): Flow<Int>

    /** Total article count for one feed, regardless of read state. */
    @Query("SELECT COUNT(*) FROM sync_articles WHERE feed_id = :feedId")
    fun observeCountByFeed(feedId: Int): Flow<Int>

    // ---- Cursor persistence ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: SyncMetaEntity)

    @Query("SELECT cursor FROM sync_meta WHERE id = 1")
    suspend fun getCursor(): Long?

    @Query("DELETE FROM sync_meta")
    suspend fun clearMeta()

    // ---- Offline mutation queue (ticket #107 / FU-2) ----

    /** Upsert pending read-state changes (overwrites any id already queued). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueMutations(mutations: List<PendingMutationEntity>)

    /**
     * Remove acked mutations, only for ids whose queued value still matches [isRead]
     * (the `AND is_read = :isRead` clause is the per-id value guard). Callers pass a
     * uniform-[isRead] chunk under the SQLite host-param limit.
     */
    @Query("DELETE FROM pending_mutations WHERE id IN (:ids) AND is_read = :isRead")
    suspend fun dequeueMutations(ids: List<Int>, isRead: Boolean)

    /** Return all pending mutations. */
    @Query("SELECT * FROM pending_mutations")
    suspend fun pendingMutations(): List<PendingMutationEntity>
}
