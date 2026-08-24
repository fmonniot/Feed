package eu.monniot.feed.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity mirroring the display-relevant subset of the shared
 * [eu.monniot.feed.shared.api.Feed] model. Only fields needed to resolve
 * [eu.monniot.feed.shared.ArticleItem.feedTitle] offline are persisted — everything else
 * about a feed (pause state, fetch interval, health, ordering, ...) is always sourced live
 * from the server and never read from this table.
 */
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey val id: Int,
    val url: String,
    val title: String?,
    @ColumnInfo(name = "custom_title") val customTitle: String?,
)
