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
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddRequest
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.FeedCategoryUpdateRequest
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.FeedUpdateRequest
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RetentionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Locale

internal fun toEntities(
    articles: List<Article>,
    feedTitlesById: Map<Int, String?>
): List<RssItemEntity> {
    val dateFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
    return articles.map { article ->
        RssItemEntity(
            id = article.id.toString(),
            title = article.title ?: "Untitled",
            description = article.content.orEmpty(),
            pubDate = article.published?.let { dateFormat.format(java.util.Date(it * 1000)) } ?: "",
            source = "Feed",
            url = article.link.orEmpty(),
            timestamp = article.published?.let { it * 1000 } ?: 0L,
            feedTitle = feedTitlesById[article.feed_id],
            isRead = article.is_read,
            linkStatus = article.link_status,
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
    val feedTitle: String? = null,
    val isRead: Boolean = false,
    val linkStatus: Int? = null,
)

// -- Room DAO --

@Dao
interface RssItemDao {
    @Query("SELECT * FROM rss_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<RssItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RssItemEntity>)

    @Query("UPDATE rss_items SET isRead = :isRead WHERE id = :id")
    suspend fun updateReadStatus(id: String, isRead: Boolean)

    @Query("DELETE FROM rss_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM rss_items")
    suspend fun clearAll()
}

// -- Room Database --

@Database(entities = [RssItemEntity::class], version = 5)
abstract class FeedDatabase : RoomDatabase() {
    abstract fun rssItemDao(): RssItemDao

    companion object {
        @Volatile
        private var INSTANCE: FeedDatabase? = null

        // Migrations are `internal` (not `private`) so RoomMigrationTest can drive
        // them directly through MigrationTestHelper.
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_items ADD COLUMN url TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_items ADD COLUMN feedTitle TEXT")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_items ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_items ADD COLUMN linkStatus INTEGER")
            }
        }

        fun getDatabase(context: Context): FeedDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FeedDatabase::class.java,
                    "feed_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// -- Repository --

class FeedRepository(
    private val api: FeedApi,
    private val rssItemDao: RssItemDao
) : FeedRepository {

    override val items: Flow<List<ArticleItem>> = rssItemDao.getAllItems().map { entities ->
        entities.map { e ->
            ArticleItem(
                id = e.id,
                title = e.title,
                description = e.description,
                pubDate = e.pubDate,
                source = e.source,
                url = e.url,
                feedTitle = e.feedTitle,
                isRead = e.isRead,
                linkStatus = e.linkStatus,
            )
        }
    }

    override suspend fun refresh() {
        val articles = api.getArticles().data
        val feedTitlesById = api.getFeeds().data
            .associate { it.id to (it.custom_title ?: it.title ?: it.url) }
        rssItemDao.insertAll(toEntities(articles, feedTitlesById))
    }

    override suspend fun refreshUpstream(): RefreshResult = api.refreshAllFeeds()

    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult =
        api.refreshFeed(feedId)

    override suspend fun markAsRead(articleId: Int) {
        api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
        rssItemDao.updateReadStatus(articleId.toString(), true)
    }

    override suspend fun markAsUnread(articleId: Int) {
        api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = false))
        rssItemDao.updateReadStatus(articleId.toString(), false)
    }

    override suspend fun getFeeds(): List<Feed> = api.getFeeds().data

    override suspend fun addFeed(url: String): FeedAddResponse =
        api.addFeed(FeedAddRequest(url)).data

    override suspend fun updateFeed(
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

    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {
        api.updateFeed(feedId, FeedUpdateRequest(url = newUrl))
    }

    override suspend fun deleteFeed(feedId: Int) {
        api.deleteFeed(feedId)
    }

    override suspend fun getCategories(): List<Category> = api.getCategories().data

    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {
        api.setFeedCategory(feedId, FeedCategoryUpdateRequest(category_id = categoryId))
    }

    override suspend fun importOpml(opmlText: String): OpmlImportResult =
        api.importOpml(opmlText).data

    override suspend fun getServerVersion(): String =
        api.getVersion().version

    override suspend fun getParseError(feedId: Int): FeedParseError? =
        api.getParseError(feedId)?.data

    override suspend fun clearArticles() {
        rssItemDao.clearAll()
    }

    override suspend fun getRetention(): Int? =
        api.getRetention().days

    override suspend fun setRetention(days: Int?) {
        api.setRetention(RetentionRequest(days = days))
    }
}
