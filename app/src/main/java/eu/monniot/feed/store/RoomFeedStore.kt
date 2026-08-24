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

    override suspend fun replaceAll(feeds: List<Feed>) {
        db.withTransaction {
            if (feeds.isEmpty()) {
                dao.clear()
            } else {
                dao.upsert(feeds.map { it.toEntity() })
                dao.deleteAllExcept(feeds.map { it.id })
            }
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
