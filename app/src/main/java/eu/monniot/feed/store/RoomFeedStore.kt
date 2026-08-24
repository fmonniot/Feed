package eu.monniot.feed.store

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import eu.monniot.feed.shared.api.Feed
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

    override fun observeAll(): Flow<Map<Int, Feed>> =
        dao.observeAll().map { entities -> entities.associate { it.id to it.toFeed() } }
}

// ---- Mapping helpers ----

private fun Feed.toEntity() = FeedEntity(
    id = id,
    url = url,
    title = title,
    customTitle = custom_title,
)

/**
 * Reconstructs a [Feed] carrying only the persisted display fields (id/url/title/custom_title).
 * The remaining fields are never read from this store — only [FeedEntity]'s cache backs
 * [eu.monniot.feed.shared.ArticleItem.feedTitle] resolution — so placeholder defaults are safe.
 */
private fun FeedEntity.toFeed() = Feed(
    id = id,
    url = url,
    title = title,
    custom_title = customTitle,
    is_paused = false,
    fetch_interval_minutes = 60,
    error_count = 0,
    last_fetched = null,
    category_id = null,
)
