package eu.monniot.feed

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// -- Room Entity --

@Entity(tableName = "rss_items")
data class RssItemEntity(
    @PrimaryKey val id: String, 
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val timestamp: Long // For ordering
)

// -- Room DAO --

@Dao
interface RssItemDao {
    @Query("SELECT * FROM rss_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<RssItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RssItemEntity>)

    @Query("DELETE FROM rss_items")
    suspend fun clearAll()
}

// -- Room Database --

@Database(entities = [RssItemEntity::class], version = 1)
abstract class FeedDatabase : RoomDatabase() {
    abstract fun rssItemDao(): RssItemDao

    companion object {
        @Volatile
        private var INSTANCE: FeedDatabase? = null

        fun getDatabase(context: Context): FeedDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FeedDatabase::class.java,
                    "feed_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// -- Repository --

class FeedRepository(
    private val feedlyFacade: FeedlyFacade,
    private val rssItemDao: RssItemDao
) {

    // Source of truth is the local database
    val items: Flow<List<RssItemEntity>> = rssItemDao.getAllItems()

    /**
     * Refreshes the data from Feedly and updates the local cache.
     * @param streamId The Feedly stream ID to fetch.
     */
    suspend fun refresh(streamId: String) {
        val remoteItems = feedlyFacade.getStreamItems(streamId)
        
        // Convert RssItem (UI/Domain model) to RssItemEntity (DB model)
        val entities = remoteItems.map { item ->
            RssItemEntity(
                id = item.id, // Using Feedly ID now
                title = item.title,
                description = item.description,
                pubDate = item.pubDate,
                source = item.source,
                timestamp = System.currentTimeMillis() // This might be better served by parsing pubDate, but keeping as is for now
            )
        }
        
        // Simple strategy: Clear and Insert (or just Insert with Replace)
        // Here we insert/replace.
        rssItemDao.insertAll(entities)
    }
}
