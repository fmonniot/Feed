package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.OpmlImportResult
import kotlinx.coroutines.flow.Flow

data class ArticleItem(
    val id: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val url: String,
    val feedTitle: String?,
    // Extended fields for the new UI
    val feedId: Int = 0,
    val feedHue: Int = 0,
    val isRead: Boolean = false,
    val author: String? = null,
    val minutesToRead: Int = 1,
    val excerpt: String = "",
)

interface FeedRepository {
    val items: Flow<List<ArticleItem>>
    suspend fun refresh()
    suspend fun markAsRead(articleId: Int)
    suspend fun markAsUnread(articleId: Int)
    suspend fun getFeeds(): List<Feed>
    suspend fun addFeed(url: String): FeedAddResponse
    suspend fun updateFeed(
        feedId: Int,
        customTitle: String?,
        fetchIntervalMinutes: Int,
        isPaused: Boolean,
    )
    suspend fun deleteFeed(feedId: Int)
    suspend fun getCategories(): List<Category>
    suspend fun setFeedCategory(feedId: Int, categoryId: Int?)
    suspend fun importOpml(opmlText: String): OpmlImportResult
    suspend fun getServerVersion(): String
}
