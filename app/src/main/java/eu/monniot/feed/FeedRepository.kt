package eu.monniot.feed

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.monniot.feed.api.Article
import eu.monniot.feed.api.ArticleReadUpdateRequest
import eu.monniot.feed.api.Feed
import eu.monniot.feed.api.FeedAddRequest
import eu.monniot.feed.api.FeedAddResponse
import eu.monniot.feed.api.FeedUpdateRequest
import eu.monniot.feed.api.FeedV1Api
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure projection from network articles + feed-title lookup to Room entities.
 * Articles whose `feed_id` isn't in [feedTitlesById] get `feedTitle = null`
 * so the UI can show "Unknown" rather than crashing.
 */
internal fun toEntities(
    articles: List<Article>,
    feedTitlesById: Map<Int, String>
): List<RssItemEntity> {
    val dateFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
    return articles.map { article ->
        RssItemEntity(
            id = article.id.toString(),
            title = article.title,
            description = article.content,
            pubDate = dateFormat.format(java.util.Date(article.published * 1000)),
            source = "Feed",
            url = article.link,
            timestamp = article.published * 1000,
            feedTitle = feedTitlesById[article.feed_id]
        )
    }
}

// -- Room Entity --

@Entity(tableName = "rss_items")
data class RssItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val url: String,
    val timestamp: Long,
    val feedTitle: String? = null
)

// -- Room DAO --

@Dao
interface RssItemDao {
    @Query("SELECT * FROM rss_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<RssItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RssItemEntity>)

    @Query("DELETE FROM rss_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM rss_items")
    suspend fun clearAll()
}

// -- Room Database --

@Database(entities = [RssItemEntity::class], version = 3)
abstract class FeedDatabase : RoomDatabase() {
    abstract fun rssItemDao(): RssItemDao

    companion object {
        @Volatile
        private var INSTANCE: FeedDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_items ADD COLUMN url TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing rows keep feedTitle = NULL so the UI shows "Unknown"
                // until the next refresh populates them.
                db.execSQL("ALTER TABLE rss_items ADD COLUMN feedTitle TEXT")
            }
        }

        fun getDatabase(context: Context): FeedDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FeedDatabase::class.java,
                    "feed_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// -- Repository --

class FeedRepository(
    private val api: FeedV1Api,
    private val rssItemDao: RssItemDao
) {

    // Source of truth is the local database
    val items: Flow<List<RssItemEntity>> = rssItemDao.getAllItems()

    /**
     * Refreshes the data from our Server API and updates the local cache.
     * Throws on network errors so callers can show appropriate UI feedback.
     */
    suspend fun refresh() {
        val articles = api.getArticles(isRead = false).data
        // One feeds fetch per refresh — far cheaper than per-article lookups.
        val feedTitlesById = api.getFeeds().data
            .associate { it.id to (it.custom_title ?: it.title) }
        rssItemDao.insertAll(toEntities(articles, feedTitlesById))
    }

    /**
     * Marks an article as read on the server and removes it from the local cache.
     */
    suspend fun markAsRead(articleId: Int) {
        api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
        rssItemDao.deleteById(articleId.toString())
    }

    suspend fun getFeeds(): List<Feed> =
        api.getFeeds().data

    suspend fun addFeed(url: String): FeedAddResponse =
        api.addFeed(FeedAddRequest(url)).data

    // Always sends all three fields — the server's serde defaults (interval=30, paused=false)
    // would clobber unchanged fields if we sent only the modified one.
    suspend fun updateFeed(
        feedId: Int,
        customTitle: String?,
        fetchIntervalMinutes: Int,
        isPaused: Boolean,
    ) {
        api.updateFeed(
            feedId,
            FeedUpdateRequest(
                custom_title = customTitle,
                fetch_interval_minutes = fetchIntervalMinutes,
                is_paused = isPaused,
            )
        )
    }

    suspend fun deleteFeed(feedId: Int) {
        api.deleteFeed(feedId)
    }
}
