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

    @Query("DELETE FROM sync_articles")
    suspend fun clearArticles()

    // ---- Read side: paged observations ----

    /**
     * All articles, ordered `published DESC NULLS LAST, seq DESC`, windowed.
     * SQLite sorts NULLs first in DESC by default; `CASE WHEN published IS NULL`
     * pushes them to the bottom.
     */
    @Query("""
        SELECT * FROM sync_articles
        ORDER BY CASE WHEN published IS NULL THEN 1 ELSE 0 END,
                 published DESC,
                 seq DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observePageAll(limit: Int, offset: Int): Flow<List<SyncArticleEntity>>

    /**
     * Unread articles only, same ordering.
     */
    @Query("""
        SELECT * FROM sync_articles
        WHERE is_read = 0
        ORDER BY CASE WHEN published IS NULL THEN 1 ELSE 0 END,
                 published DESC,
                 seq DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observePageUnread(limit: Int, offset: Int): Flow<List<SyncArticleEntity>>

    /**
     * Articles for a specific feed, same ordering.
     */
    @Query("""
        SELECT * FROM sync_articles
        WHERE feed_id = :feedId
        ORDER BY CASE WHEN published IS NULL THEN 1 ELSE 0 END,
                 published DESC,
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
