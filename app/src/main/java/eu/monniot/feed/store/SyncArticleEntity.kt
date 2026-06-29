package eu.monniot.feed.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity mirroring the shared [eu.monniot.feed.shared.api.Article] model for
 * the local sync mirror. The legacy `rss_items` table was dropped in #103.
 *
 * Ordering is `sort_published DESC, seq DESC`. The `sort_published` column
 * materializes `COALESCE(published, 0)` so the `ORDER BY` can be satisfied by
 * an index walk instead of a temp B-tree sort. NULL published values map to 0
 * (epoch), which is older than any real timestamp, achieving NULLs-last in DESC.
 */
@Entity(
    tableName = "sync_articles",
    indices = [
        Index(value = ["sort_published", "seq"]),
        Index(value = ["feed_id"]),
    ]
)
data class SyncArticleEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "feed_id") val feedId: Int,
    val guid: String,
    val title: String?,
    val content: String?,
    val link: String?,
    val author: String?,
    val published: Long?,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long?,
    @ColumnInfo(name = "link_status") val linkStatus: Int?,
    @ColumnInfo(name = "link_checked_at") val linkCheckedAt: Long?,
    val seq: Long,
    /** Materialized sort key: `COALESCE(published, 0)`. Indexed with `seq`. */
    @ColumnInfo(name = "sort_published", defaultValue = "0") val sortPublished: Long = 0,
)
