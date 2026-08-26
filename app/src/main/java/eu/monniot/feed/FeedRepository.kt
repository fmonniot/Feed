package eu.monniot.feed

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import eu.monniot.feed.store.ArticleStoreDao
import eu.monniot.feed.store.CategoryDao
import eu.monniot.feed.store.CategoryEntity
import eu.monniot.feed.store.FeedDao
import eu.monniot.feed.store.FeedEntity
import eu.monniot.feed.store.PendingMutationEntity
import eu.monniot.feed.store.SyncArticleEntity
import eu.monniot.feed.store.SyncMetaEntity

// -- Room Database --

@Database(
    entities = [
        SyncArticleEntity::class, SyncMetaEntity::class, PendingMutationEntity::class,
        FeedEntity::class, CategoryEntity::class,
    ],
    version = 11,
)
abstract class FeedDatabase : RoomDatabase() {
    abstract fun articleStoreDao(): ArticleStoreDao
    abstract fun feedDao(): FeedDao
    abstract fun categoryDao(): CategoryDao

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

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add a materialized sort column so ORDER BY can use an index walk
                // instead of a temp B-tree sort (BUG-36). NULL published values map
                // to 0 (epoch), which sorts last in DESC order.
                db.execSQL("ALTER TABLE sync_articles ADD COLUMN sort_published INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sync_articles SET sort_published = COALESCE(published, 0)")
                db.execSQL("DROP INDEX IF EXISTS index_sync_articles_published_seq")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_articles_sort_published_seq ON sync_articles (sort_published, seq)")
            }
        }

        // ticket #107 / FU-2: persistent offline read-state mutation queue.
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_mutations (
                        id INTEGER NOT NULL PRIMARY KEY,
                        is_read INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // BUG-62: persist feed metadata so ArticleItem.feedTitle
        // survives process death / offline sessions instead of falling back to
        // "Unknown" once the old in-memory-only cache is empty.
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS feeds (
                        id INTEGER NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL,
                        title TEXT,
                        custom_title TEXT
                    )
                """.trimIndent())
            }
        }

        // BUG-63 part 2: widen the `feeds` table with the fields an offline Feeds screen
        // needs for folder grouping + a (point-in-time) health indicator, and add a
        // `categories` table so the category list also survives offline — see
        // eu.monniot.feed.shared.sync.FeedMeta's doc comment for why exactly these fields.
        // Existing rows get NOT NULL DEFAULT values for is_paused/error_count so the ALTER
        // succeeds without a backfill query; a feed's next replaceAll() (the very next
        // getFeeds() call) overwrites them with real values regardless.
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN category_id INTEGER")
                db.execSQL("ALTER TABLE feeds ADD COLUMN is_paused INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE feeds ADD COLUMN error_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE feeds ADD COLUMN server_feed_status TEXT")
                db.execSQL("ALTER TABLE feeds ADD COLUMN severity TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS categories (
                        id INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        position INTEGER NOT NULL
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
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11,
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

