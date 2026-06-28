package eu.monniot.feed

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
}
