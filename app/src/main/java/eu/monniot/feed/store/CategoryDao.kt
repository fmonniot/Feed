package eu.monniot.feed.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room DAO backing the [eu.monniot.feed.shared.sync.CategoryStore] contract. */
@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun clear()

    @Query("SELECT * FROM categories ORDER BY position")
    fun observeAll(): Flow<List<CategoryEntity>>
}
