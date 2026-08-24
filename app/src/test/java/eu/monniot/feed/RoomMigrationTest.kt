package eu.monniot.feed

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the Room schema migrations declared in [FeedDatabase].
 *
 * Schemas are exported to `app/schemas/` via the `room.schemaLocation` ksp arg
 * and surfaced to Robolectric through `src/test/assets` (see build.gradle.kts),
 * which is where [MigrationTestHelper] loads them from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FeedDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * 4 -> 5 adds the nullable `linkStatus` column. Starting from a v4 database
     * (no `linkStatus`), running the migration must add the column and validate
     * against the generated v5 schema.
     */
    @Test
    fun migrate4To5_addsLinkStatusColumn() {
        // Create the database at version 4 and seed one row without linkStatus.
        helper.createDatabase(testDb, 4).apply {
            execSQL(
                "INSERT INTO rss_items " +
                    "(id, title, description, pubDate, source, url, timestamp, feedTitle, isRead) " +
                    "VALUES ('a1', 'Title', 'Body', 'Mon, 1 Jan 2024', 'Feed', " +
                    "'https://example.com/a1', 1700000000000, 'Example', 0)"
            )
            close()
        }

        // Run the 4 -> 5 migration and validate the resulting schema matches v5.
        val db = helper.runMigrationsAndValidate(
            testDb,
            5,
            true,
            FeedDatabase.MIGRATION_4_5,
        )

        // The linkStatus column now exists and is null for the pre-existing row.
        db.query("SELECT linkStatus FROM rss_items WHERE id = 'a1'").use { cursor ->
            assertTrue("expected one row after migration", cursor.moveToFirst())
            assertTrue("linkStatus should be null for migrated rows", cursor.isNull(0))
        }

        // The pre-existing data survived the migration intact.
        db.query("SELECT title FROM rss_items WHERE id = 'a1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Title", cursor.getString(0))
        }
        db.close()
    }

    /**
     * 5 -> 6 creates the `sync_articles` and `sync_meta` tables. Starting from
     * a v5 database, running the migration must create both tables and validate
     * against the generated v6 schema.
     */
    @Test
    fun migrate5To6_createsSyncTables() {
        // Create the database at version 5 with one rss_items row.
        helper.createDatabase(testDb, 5).apply {
            execSQL(
                "INSERT INTO rss_items " +
                    "(id, title, description, pubDate, source, url, timestamp, feedTitle, isRead, linkStatus) " +
                    "VALUES ('a1', 'Title', 'Body', 'Mon, 1 Jan 2024', 'Feed', " +
                    "'https://example.com/a1', 1700000000000, 'Example', 0, NULL)"
            )
            close()
        }

        // Run the 5 -> 6 migration and validate the resulting schema matches v6.
        val db = helper.runMigrationsAndValidate(
            testDb,
            6,
            true,
            FeedDatabase.MIGRATION_5_6,
        )

        // The sync_articles table exists and can accept rows.
        db.execSQL(
            "INSERT INTO sync_articles " +
                "(id, feed_id, guid, title, content, link, author, published, is_read, fetched_at, link_status, link_checked_at, seq) " +
                "VALUES (1, 10, 'guid-1', 'Sync Title', 'body', 'https://example.com/1', 'Author', 1700000000, 0, 1700001000, NULL, NULL, 42)"
        )
        db.query("SELECT id, feed_id, seq FROM sync_articles WHERE id = 1").use { cursor ->
            assertTrue("expected one row in sync_articles", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(10, cursor.getInt(1))
            assertEquals(42, cursor.getLong(2))
        }

        // The sync_meta table exists and can accept a cursor row.
        db.execSQL("INSERT INTO sync_meta (id, cursor) VALUES (1, 99)")
        db.query("SELECT cursor FROM sync_meta WHERE id = 1").use { cursor ->
            assertTrue("expected one row in sync_meta", cursor.moveToFirst())
            assertEquals(99, cursor.getLong(0))
        }

        // The pre-existing rss_items data survived the migration intact.
        db.query("SELECT title FROM rss_items WHERE id = 'a1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Title", cursor.getString(0))
        }

        db.close()
    }

    /**
     * 7 -> 8 adds the `sort_published` materialized sort column to
     * `sync_articles`, backfills it with `COALESCE(published, 0)`, drops the
     * old `(published, seq)` index and creates a new `(sort_published, seq)`
     * index so ORDER BY can use an index walk (BUG-36).
     */
    @Test
    fun migrate7To8_addsSortPublishedColumn() {
        // Create the database at version 7 with articles having various published values.
        helper.createDatabase(testDb, 7).apply {
            execSQL(
                "INSERT INTO sync_articles " +
                    "(id, feed_id, guid, title, content, link, author, published, is_read, fetched_at, link_status, link_checked_at, seq) " +
                    "VALUES (1, 10, 'guid-1', 'With published', 'body', 'https://example.com/1', 'Author', 1700000000, 0, 1700001000, NULL, NULL, 42)"
            )
            execSQL(
                "INSERT INTO sync_articles " +
                    "(id, feed_id, guid, title, content, link, author, published, is_read, fetched_at, link_status, link_checked_at, seq) " +
                    "VALUES (2, 10, 'guid-2', 'Null published', 'body', 'https://example.com/2', 'Author', NULL, 0, 1700002000, NULL, NULL, 43)"
            )
            close()
        }

        // Run the 7 -> 8 migration and validate the resulting schema matches v8.
        val db = helper.runMigrationsAndValidate(
            testDb,
            8,
            true,
            FeedDatabase.MIGRATION_7_8,
        )

        // sort_published is COALESCE(published, 0) for both rows.
        db.query("SELECT id, sort_published FROM sync_articles ORDER BY id").use { cursor ->
            assertTrue("expected two rows", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(1700000000L, cursor.getLong(1))

            assertTrue("expected second row", cursor.moveToNext())
            assertEquals(2, cursor.getInt(0))
            assertEquals(0L, cursor.getLong(1))
        }

        // The old index is gone, the new one exists.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sync_articles' ORDER BY name"
        ).use { cursor ->
            val indexes = mutableListOf<String>()
            while (cursor.moveToNext()) {
                indexes.add(cursor.getString(0))
            }
            assertFalse(
                "old (published, seq) index must be dropped",
                indexes.contains("index_sync_articles_published_seq")
            )
            assertTrue(
                "new (sort_published, seq) index must exist",
                indexes.contains("index_sync_articles_sort_published_seq")
            )
        }

        db.close()
    }

    /**
     * 8 -> 9 creates the `pending_mutations` table (ticket #107 / FU-2).
     *
     * Uses [MigrationTestHelper.runMigrationsAndValidate] with the exported v9
     * schema JSON (committed at app/schemas/.../9.json). Beyond checking the table
     * exists, this validates that the hand-written SQL in [FeedDatabase.MIGRATION_8_9]
     * matches exactly what Room expects for `PendingMutationEntity` — an
     * affinity/constraint mismatch would fail here rather than crashing migrated
     * users at DB-open time.
     */
    @Test
    fun migrate8To9_createsPendingMutationsTable() {
        // Create the database at version 8 and seed one article.
        helper.createDatabase(testDb, 8).apply {
            execSQL(
                "INSERT INTO sync_articles " +
                    "(id, feed_id, guid, title, content, link, author, published, is_read, fetched_at, " +
                    "link_status, link_checked_at, seq, sort_published) " +
                    "VALUES (1, 10, 'guid-1', 'Title', 'body', 'https://example.com/1', 'Author', " +
                    "1700000000, 0, 1700001000, NULL, NULL, 42, 1700000000)"
            )
            close()
        }

        // Run the 8 -> 9 migration and validate the resulting schema matches v9.
        val db = helper.runMigrationsAndValidate(
            testDb,
            9,
            true,
            FeedDatabase.MIGRATION_8_9,
        )

        // The pending_mutations table must now exist.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='pending_mutations'"
        ).use { cursor ->
            assertTrue("pending_mutations table must exist after migration", cursor.moveToFirst())
        }

        // The table must accept rows and store both id and is_read correctly.
        db.execSQL("INSERT INTO pending_mutations (id, is_read) VALUES (42, 1)")
        db.query("SELECT id, is_read FROM pending_mutations WHERE id = 42").use { cursor ->
            assertTrue("expected one row in pending_mutations", cursor.moveToFirst())
            assertEquals(42, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))  // is_read = true (SQLite stores as 1)
        }

        // Pre-existing sync_articles data survived the migration intact.
        db.query("SELECT id, seq FROM sync_articles WHERE id = 1").use { cursor ->
            assertTrue("expected one row in sync_articles", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(42, cursor.getLong(1))
        }

        db.close()
    }

    /**
     * 6 -> 7 drops the legacy `rss_items` table. Starting from a v6 database
     * that still has `rss_items`, running the migration must drop the table
     * while leaving the `sync_articles` and `sync_meta` tables intact.
     */
    @Test
    fun migrate6To7_dropsRssItemsTable() {
        // Create the database at version 6 with rows in both rss_items and sync_articles.
        helper.createDatabase(testDb, 6).apply {
            execSQL(
                "INSERT INTO rss_items " +
                    "(id, title, description, pubDate, source, url, timestamp, feedTitle, isRead, linkStatus) " +
                    "VALUES ('a1', 'Old Title', 'Body', 'Mon, 1 Jan 2024', 'Feed', " +
                    "'https://example.com/a1', 1700000000000, 'Example', 0, NULL)"
            )
            execSQL(
                "INSERT INTO sync_articles " +
                    "(id, feed_id, guid, title, content, link, author, published, is_read, fetched_at, link_status, link_checked_at, seq) " +
                    "VALUES (1, 10, 'guid-1', 'Sync Title', 'body', 'https://example.com/1', 'Author', 1700000000, 0, 1700001000, NULL, NULL, 42)"
            )
            execSQL("INSERT INTO sync_meta (id, cursor) VALUES (1, 99)")
            close()
        }

        // Run the 6 -> 7 migration and validate the resulting schema matches v7.
        val db = helper.runMigrationsAndValidate(
            testDb,
            7,
            true,
            FeedDatabase.MIGRATION_6_7,
        )

        // The rss_items table must be gone.
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='rss_items'").use { cursor ->
            assertFalse("rss_items table must not exist after migration", cursor.moveToFirst())
        }

        // The sync_articles data survived the migration intact.
        db.query("SELECT id, feed_id, seq FROM sync_articles WHERE id = 1").use { cursor ->
            assertTrue("expected one row in sync_articles", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(10, cursor.getInt(1))
            assertEquals(42, cursor.getLong(2))
        }

        // The sync_meta data survived the migration intact.
        db.query("SELECT cursor FROM sync_meta WHERE id = 1").use { cursor ->
            assertTrue("expected one row in sync_meta", cursor.moveToFirst())
            assertEquals(99, cursor.getLong(0))
        }

        db.close()
    }

    /**
     * 9 -> 10 creates the `feeds` table (BUG-offline-feed-name): persists feed
     * id/url/title/custom_title so [eu.monniot.feed.shared.ArticleItem.feedTitle]
     * survives process death instead of falling back to "Unknown" once the old
     * in-memory-only feeds cache is empty.
     */
    @Test
    fun migrate9To10_createsFeedsTable() {
        // Create the database at version 9 and seed one article.
        helper.createDatabase(testDb, 9).apply {
            execSQL(
                "INSERT INTO sync_articles " +
                    "(id, feed_id, guid, title, content, link, author, published, is_read, fetched_at, " +
                    "link_status, link_checked_at, seq, sort_published) " +
                    "VALUES (1, 10, 'guid-1', 'Title', 'body', 'https://example.com/1', 'Author', " +
                    "1700000000, 0, 1700001000, NULL, NULL, 42, 1700000000)"
            )
            close()
        }

        // Run the 9 -> 10 migration and validate the resulting schema matches v10.
        val db = helper.runMigrationsAndValidate(
            testDb,
            10,
            true,
            FeedDatabase.MIGRATION_9_10,
        )

        // The feeds table must now exist and accept rows.
        db.execSQL(
            "INSERT INTO feeds (id, url, title, custom_title) " +
                "VALUES (10, 'https://example.com/feed', 'Tech Blog', 'My Tech')"
        )
        db.query("SELECT url, title, custom_title FROM feeds WHERE id = 10").use { cursor ->
            assertTrue("expected one row in feeds", cursor.moveToFirst())
            assertEquals("https://example.com/feed", cursor.getString(0))
            assertEquals("Tech Blog", cursor.getString(1))
            assertEquals("My Tech", cursor.getString(2))
        }

        // Pre-existing sync_articles data survived the migration intact.
        db.query("SELECT id, feed_id, seq FROM sync_articles WHERE id = 1").use { cursor ->
            assertTrue("expected one row in sync_articles", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(10, cursor.getInt(1))
            assertEquals(42, cursor.getLong(2))
        }

        db.close()
    }
}
