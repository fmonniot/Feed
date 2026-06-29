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

    @Query("UPDATE sync_articles SET is_read = :isRead WHERE id = :id")
    suspend fun markRead(id: Int, isRead: Boolean)

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
     * Unread articles only, same ordering.
     * Note: the `(sort_published, seq)` index only covers the unfiltered path;
     * this query still requires a temp sort due to the `WHERE is_read = 0` filter.
     */
    @Query("""
        SELECT * FROM sync_articles
        WHERE is_read = 0
        ORDER BY sort_published DESC,
                 seq DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observePageUnread(limit: Int, offset: Int): Flow<List<SyncArticleEntity>>

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

    // ---- Read side: aggregate counts ----

    @Query("SELECT COUNT(*) FROM sync_articles WHERE is_read = 0")
    fun observeUnreadCountAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_articles WHERE is_read = 0 AND feed_id = :feedId")
    fun observeUnreadCountByFeed(feedId: Int): Flow<Int>

    // ---- Cursor persistence ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: SyncMetaEntity)

    @Query("SELECT cursor FROM sync_meta WHERE id = 1")
    suspend fun getCursor(): Long?

    @Query("DELETE FROM sync_meta")
    suspend fun clearMeta()
}
