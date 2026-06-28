package eu.monniot.feed.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One-row table persisting the sync cursor (the highest `seq` value received
 * from the server). Fresh install starts at cursor = 0.
 *
 * The [id] is always 1 — an upsert by primary key keeps exactly one row.
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val id: Int = 1,
    val cursor: Long = 0,
)
