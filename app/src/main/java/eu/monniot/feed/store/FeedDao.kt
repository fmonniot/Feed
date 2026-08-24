package eu.monniot.feed.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room DAO backing the [eu.monniot.feed.shared.sync.FeedStore] contract. */
@Dao
interface FeedDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feeds: List<FeedEntity>)

    @Query("DELETE FROM feeds")
    suspend fun clear()

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT * FROM feeds")
    fun observeAll(): Flow<List<FeedEntity>>
}
