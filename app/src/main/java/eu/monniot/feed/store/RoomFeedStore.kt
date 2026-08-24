package eu.monniot.feed.store

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.sync.FeedMeta
import eu.monniot.feed.shared.sync.FeedStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [FeedStore].
 *
 * Maps between the shared [Feed] model and [FeedEntity], delegates all persistence to
 * [FeedDao]. Fixes the offline "Unknown" feed name bug: unlike the previous in-memory-only
 * cache, this survives process death, so [eu.monniot.feed.shared.ArticleItem.feedTitle]
 * still resolves for cached articles read while offline after a cold start.
 */
class RoomFeedStore(private val db: RoomDatabase, private val dao: FeedDao) : FeedStore {

    /**
     * Clear-then-insert rather than upsert-then-prune. A `DELETE ... WHERE id NOT IN (:ids)`
     * prune binds one SQL variable per feed id, and Room does not chunk `IN (:list)`
     * parameters — a user with more feeds than `SQLITE_MAX_VARIABLE_NUMBER` (999 below API 32)
     * would hit "too many SQL variables" on every feed-list load. The whole pair runs in one
     * transaction, so Room fires invalidation once at commit and [observeAll] never emits the
     * intermediate empty state.
     */
    override suspend fun replaceAll(feeds: List<Feed>) {
        db.withTransaction {
            dao.clear()
            if (feeds.isNotEmpty()) dao.upsert(feeds.map { it.toEntity() })
        }
    }

    override suspend fun deleteById(id: Int) {
        dao.deleteById(id)
    }

    override fun observeAll(): Flow<Map<Int, FeedMeta>> =
        dao.observeAll().map { entities -> entities.associate { it.id to it.toFeedMeta() } }
}

// ---- Mapping helpers ----

private fun Feed.toEntity() = FeedEntity(
    id = id,
    url = url,
    title = title,
    customTitle = custom_title,
)

/**
 * [FeedEntity] and [FeedMeta] hold the same four persisted display fields, so this is a
 * total mapping — no field is invented, which is the point of returning [FeedMeta] rather
 * than a [Feed] whose other five fields this table cannot honour.
 */
private fun FeedEntity.toFeedMeta() = FeedMeta(
    id = id,
    url = url,
    title = title,
    customTitle = customTitle,
)
