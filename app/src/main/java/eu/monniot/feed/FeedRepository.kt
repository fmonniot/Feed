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
import eu.monniot.feed.api.ArticleReadUpdateRequest
import eu.monniot.feed.api.FeedV1Api
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Locale

// -- Room Entity --

@Entity(tableName = "rss_items")
data class RssItemEntity(
    @PrimaryKey val id: String, 
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val url: String, // Added URL
    val timestamp: Long // For ordering
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

@Database(entities = [RssItemEntity::class], version = 2) // Incremented version
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

        fun getDatabase(context: Context): FeedDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FeedDatabase::class.java,
                    "feed_database"
                )
                .addMigrations(MIGRATION_1_2)
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
        val response = api.getArticles(isRead = false)
        val remoteArticles = response.data

        // Convert Article (Network model) to RssItemEntity (DB model)
        val entities = remoteArticles.map { article ->
            RssItemEntity(
                id = article.id.toString(),
                title = article.title,
                description = article.content,
                pubDate = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
                    .format(java.util.Date(article.published * 1000)),
                source = "Feed",
                url = article.link,
                timestamp = article.published * 1000
            )
        }

        rssItemDao.insertAll(entities)
    }

    /**
     * Marks an article as read on the server and removes it from the local cache.
     */
    suspend fun markAsRead(articleId: Int) {
        api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
        rssItemDao.deleteById(articleId.toString())
    }
}
