package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.Feed
import kotlinx.coroutines.flow.Flow

data class ArticleItem(
    val id: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val url: String,
    val feedTitle: String?,
)

interface FeedRepository {
    val items: Flow<List<ArticleItem>>
    suspend fun refresh()
    suspend fun markAsRead(articleId: Int)
    suspend fun getFeeds(): List<Feed>
    suspend fun addFeed(url: String): FeedAddResponse
    suspend fun updateFeed(
        feedId: Int,
        customTitle: String?,
        fetchIntervalMinutes: Int,
        isPaused: Boolean,
    )
    suspend fun deleteFeed(feedId: Int)
}
