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
import eu.monniot.feed.store.ArticleStoreDao
import eu.monniot.feed.store.SyncArticleEntity
import eu.monniot.feed.store.SyncMetaEntity
import eu.monniot.feed.shared.api.Article
import kotlinx.coroutines.flow.Flow
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

@Database(
    entities = [RssItemEntity::class, SyncArticleEntity::class, SyncMetaEntity::class],
    version = 6,
)
abstract class FeedDatabase : RoomDatabase() {
    abstract fun rssItemDao(): RssItemDao
    abstract fun articleStoreDao(): ArticleStoreDao

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

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create sync_articles table — faithful mirror of the shared Article model.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_articles (
                        id INTEGER NOT NULL PRIMARY KEY,
                        feed_id INTEGER NOT NULL,
                        guid TEXT NOT NULL,
                        title TEXT,
                        content TEXT,
                        link TEXT,
                        author TEXT,
                        published INTEGER,
                        is_read INTEGER NOT NULL,
                        fetched_at INTEGER,
                        link_status INTEGER,
                        link_checked_at INTEGER,
                        seq INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_articles_published_seq ON sync_articles (published, seq)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_articles_feed_id ON sync_articles (feed_id)")
                // Create sync_meta table — one-row cursor persistence.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_meta (
                        id INTEGER NOT NULL PRIMARY KEY,
                        cursor INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): FeedDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FeedDatabase::class.java,
                    "feed_database"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

