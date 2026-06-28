package eu.monniot.feed.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity mirroring the shared [eu.monniot.feed.shared.api.Article] model for
 * the local sync mirror. This is a separate table from the legacy `rss_items`
 * (which will be removed when the old FeedRepository is deleted in #103).
 *
 * Ordering is `published DESC, seq DESC` — nullable `published` sorts NULLs last.
 */
@Entity(tableName = "sync_articles")
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
)
