package eu.monniot.feed.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the offline read-state mutation queue (ticket #107 / FU-2).
 *
 * Each row records the DESIRED `is_read` state for a single article that was
 * toggled while the server was unreachable.  The primary key is the article id,
 * so a second offline toggle simply overwrites the earlier entry (last-write-wins).
 *
 * [SyncEngine] reads this table at the start of every sync, flushes each entry
 * to the server, and removes it on a successful ack.  Entries that cannot be
 * flushed (still offline) protect the corresponding article rows from being
 * overwritten by a server pull.
 */
@Entity(tableName = "pending_mutations")
data class PendingMutationEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
)
