package eu.monniot.feed

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.monniot.feed.store.ArticleStoreDao
import eu.monniot.feed.store.SyncArticleEntity
import eu.monniot.feed.store.SyncMetaEntity

// -- Room Database --

@Database(
    entities = [SyncArticleEntity::class, SyncMetaEntity::class],
    version = 7,
)
abstract class FeedDatabase : RoomDatabase() {
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

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop the legacy rss_items table — all article data now lives in
                // sync_articles, managed by SyncEngine / RoomArticleStore.
                db.execSQL("DROP TABLE IF EXISTS rss_items")
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
                    MIGRATION_5_6, MIGRATION_6_7,
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

