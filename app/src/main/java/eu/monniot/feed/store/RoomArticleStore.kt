package eu.monniot.feed.store

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [ArticleStore].
 *
 * Maps between the shared [Article] model and [SyncArticleEntity], delegates
 * all persistence to [ArticleStoreDao].
 */
class RoomArticleStore(private val db: RoomDatabase, private val dao: ArticleStoreDao) : ArticleStore {

    override suspend fun upsert(articles: List<Article>) {
        dao.upsert(articles.map { it.toEntity() })
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        ids.forEachChunk { chunk -> dao.deleteByIds(chunk) }
    }

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> {
        val offset = window.first
        val limit = window.last - window.first + 1
        val flow = when (filter) {
            is ArticleFilter.All -> dao.observePageAll(limit, offset)
            is ArticleFilter.UnreadOnly -> dao.observePageUnread(filter.keepArticleId, limit, offset)
            is ArticleFilter.ByFeed -> dao.observePageByFeed(filter.feedId, limit, offset)
        }
        return flow.map { entities -> entities.map { it.toArticle() } }
    }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> = when (filter) {
        is ArticleFilter.All -> dao.observeUnreadCountAll()
        // UnreadOnly: "unread count of unread articles" == global unread count.
        is ArticleFilter.UnreadOnly -> dao.observeUnreadCountAll()
        is ArticleFilter.ByFeed -> dao.observeUnreadCountByFeed(filter.feedId)
    }

    override fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    override fun observeCount(filter: ArticleFilter): Flow<Int> = when (filter) {
        is ArticleFilter.All -> dao.observeTotalCount()
        // UnreadOnly: "total of the unread view" == global unread count.
        is ArticleFilter.UnreadOnly -> dao.observeUnreadCountAll()
        is ArticleFilter.ByFeed -> dao.observeCountByFeed(filter.feedId)
    }

    override suspend fun cursor(): Long = dao.getCursor() ?: 0L

    override suspend fun setCursor(seq: Long) {
        dao.upsertMeta(SyncMetaEntity(id = 1, cursor = seq))
    }

    override suspend fun markRead(ids: List<Int>, isRead: Boolean) {
        if (ids.isEmpty()) return
        // Wrap the chunk loop in one transaction so the whole batch is atomic AND
        // fires exactly one InvalidationTracker tick (count observers recompute once,
        // not once per chunk).
        db.withTransaction {
            ids.forEachChunk { chunk -> dao.markRead(chunk, isRead) }
        }
    }

    override suspend fun unreadIds(filter: ArticleFilter): List<Int> = when (filter) {
        is ArticleFilter.All -> dao.unreadIdsAll()
        // UnreadOnly: the unread set of the unread view == the global unread set.
        is ArticleFilter.UnreadOnly -> dao.unreadIdsAll()
        is ArticleFilter.ByFeed -> dao.unreadIdsByFeed(filter.feedId)
    }

    override suspend fun deleteByFeedId(feedId: Int) {
        dao.deleteByFeedId(feedId)
    }

    override suspend fun clear() {
        db.withTransaction {
            dao.clearArticles()
            dao.clearMeta()
            // Note: pending_mutations are intentionally NOT cleared here.
            // They are user-generated data (offline read-state changes) that must
            // survive a full_resync so SyncEngine can flush them after re-backfill.
        }
    }

    // ---- Offline mutation queue (ticket #107 / FU-2) ----

    override suspend fun enqueueMutations(ids: List<Int>, isRead: Boolean) {
        if (ids.isEmpty()) return
        // @Insert binds one row per entity (no IN clause), so no host-param chunking needed.
        dao.enqueueMutations(ids.map { PendingMutationEntity(id = it, isRead = isRead) })
    }

    override suspend fun dequeueMutations(ids: List<Int>, isRead: Boolean) {
        if (ids.isEmpty()) return
        // The pending_mutations table isn't observed, so this is atomicity-only; still
        // chunk the IN clause (901 host params per chunk: 900 ids + 1 `isRead` < 999).
        db.withTransaction {
            ids.forEachChunk { chunk -> dao.dequeueMutations(chunk, isRead) }
        }
    }

    override suspend fun pendingMutations(): Map<Int, Boolean> =
        dao.pendingMutations().associate { it.id to it.isRead }
}

// ---- Chunking helper ----

/**
 * SQLite (via Room's `IN (:ids)` binding) caps bound parameters at 999 per
 * statement. 900 leaves headroom for a query's other bound params (e.g.
 * `dequeueMutations` binds 900 ids + 1 `isRead` = 901 < 999).
 */
private const val SQLITE_MAX_HOST_PARAMS_CHUNK = 900

/** Split into [SQLITE_MAX_HOST_PARAMS_CHUNK]-sized chunks and run [op] on each. */
private suspend fun <T> List<T>.forEachChunk(op: suspend (List<T>) -> Unit) {
    chunked(SQLITE_MAX_HOST_PARAMS_CHUNK).forEach { chunk -> op(chunk) }
}

// ---- Mapping helpers ----

internal fun Article.toEntity() = SyncArticleEntity(
    id = id,
    feedId = feed_id,
    guid = guid,
    title = title,
    content = content,
    link = link,
    author = author,
    published = published,
    isRead = is_read,
    fetchedAt = fetched_at,
    linkStatus = link_status,
    linkCheckedAt = link_checked_at,
    seq = seq,
    sortPublished = published ?: 0L,
)

internal fun SyncArticleEntity.toArticle() = Article(
    id = id,
    feed_id = feedId,
    guid = guid,
    title = title,
    content = content,
    link = link,
    author = author,
    published = published,
    is_read = isRead,
    fetched_at = fetchedAt,
    link_status = linkStatus,
    link_checked_at = linkCheckedAt,
    seq = seq,
)
