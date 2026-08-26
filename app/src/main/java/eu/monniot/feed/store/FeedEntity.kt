package eu.monniot.feed.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity mirroring the persisted subset of the shared [eu.monniot.feed.shared.api.Feed]
 * model — see [eu.monniot.feed.shared.sync.FeedMeta] for exactly which fields and why.
 *
 * BUG-62 persisted only the four display fields (id/url/title/customTitle). BUG-63 part 2
 * (migration 10->11) widened this to also cover [categoryId]/[isPaused]/[errorCount]/
 * [serverFeedStatus]/[severity], so the offline Feeds screen can group feeds into folders
 * and show a (necessarily point-in-time) health indicator. Fetch interval, ordering, and the
 * detailed error fields are still always sourced live from the server and never read from
 * this table.
 */
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey val id: Int,
    val url: String,
    val title: String?,
    @ColumnInfo(name = "custom_title") val customTitle: String?,
    @ColumnInfo(name = "category_id") val categoryId: Int?,
    @ColumnInfo(name = "is_paused") val isPaused: Boolean,
    @ColumnInfo(name = "error_count") val errorCount: Int,
    @ColumnInfo(name = "server_feed_status") val serverFeedStatus: String?,
    val severity: String?,
)
