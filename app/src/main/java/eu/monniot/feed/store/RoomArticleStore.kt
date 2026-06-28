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

    override suspend fun deleteByIds(ids: List<Int>) {
        ids.chunked(900).forEach { chunk ->
            dao.deleteByIds(chunk)
        }
    }

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<Article>> {
        val offset = window.first
        val limit = window.last - window.first + 1
        val flow = when (filter) {
            is ArticleFilter.All -> dao.observePageAll(limit, offset)
            is ArticleFilter.UnreadOnly -> dao.observePageUnread(limit, offset)
            is ArticleFilter.ByFeed -> dao.observePageByFeed(filter.feedId, limit, offset)
        }
        return flow.map { entities -> entities.map { it.toArticle() } }
    }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> = when (filter) {
        is ArticleFilter.All -> dao.observeUnreadCountAll()
        is ArticleFilter.UnreadOnly -> dao.observeUnreadCountAll()
        is ArticleFilter.ByFeed -> dao.observeUnreadCountByFeed(filter.feedId)
    }

    override suspend fun cursor(): Long = dao.getCursor() ?: 0L

    override suspend fun setCursor(seq: Long) {
        dao.upsertMeta(SyncMetaEntity(id = 1, cursor = seq))
    }

    override suspend fun clear() {
        db.withTransaction {
            dao.clearArticles()
            dao.clearMeta()
        }
    }
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
