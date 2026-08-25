package eu.monniot.feed.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity mirroring the shared [eu.monniot.feed.shared.api.Category] model in full —
 * unlike [FeedEntity], there is no narrower projection here: a category has no server-live
 * fields the way a feed does (pause state, health, ...), so the whole model is safe to
 * cache and replay offline. Backs [RoomCategoryStore] (BUG-63 part 2, migration 10->11),
 * which lets the offline Feeds screen group feeds into folders.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val position: Int,
)
