package eu.monniot.feed.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.monniot.feed.FeedDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asserts that the article-list ORDER BY for the "All" query path is satisfied
 * by an index walk on `(sort_published, seq)`, not a temp B-tree sort.
 * This is the validation for BUG-36.
 *
 * The `observePageUnread` and `observePageByFeed` queries add WHERE clauses
 * that may prevent index-only sorting, but the CASE expression is no longer
 * present so the planner *can* choose the index when statistics favor it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArticleQueryPlanTest {

    private lateinit var db: FeedDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FeedDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun explainQueryPlan(sql: String): List<String> {
        val rows = mutableListOf<String>()
        db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $sql")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                }
            }
        return rows
    }

    /**
     * The primary BUG-36 invariant: `observePageAll` must use the
     * `(sort_published, seq)` index for ordering — no temp B-tree.
     */
    @Test
    fun observePageAll_usesIndexNotTempBTree() {
        val plan = explainQueryPlan(
            "SELECT * FROM sync_articles ORDER BY sort_published DESC, seq DESC LIMIT 50 OFFSET 0"
        )
        val planText = plan.joinToString("\n")

        assertTrue(
            "Expected index_sync_articles_sort_published_seq in plan but got:\n$planText",
            plan.any { it.contains("index_sync_articles_sort_published_seq") }
        )
        assertFalse(
            "Expected no TEMP B-TREE in plan but got:\n$planText",
            plan.any { it.contains("TEMP B-TREE", ignoreCase = true) }
        )
    }

    /**
     * Verify the old CASE-based ORDER BY would have required a temp B-tree,
     * confirming the fix is meaningful.
     */
    @Test
    fun oldCaseOrderBy_wouldUseTempBTree() {
        val plan = explainQueryPlan(
            "SELECT * FROM sync_articles " +
                "ORDER BY CASE WHEN published IS NULL THEN 1 ELSE 0 END, " +
                "published DESC, seq DESC " +
                "LIMIT 50 OFFSET 0"
        )
        val planText = plan.joinToString("\n")

        assertTrue(
            "Expected TEMP B-TREE for old CASE-based ORDER BY but got:\n$planText",
            plan.any { it.contains("TEMP B-TREE", ignoreCase = true) }
        )
    }
}
