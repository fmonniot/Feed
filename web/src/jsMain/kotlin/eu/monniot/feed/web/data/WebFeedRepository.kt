package eu.monniot.feed.web.data

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddRequest
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.FeedUpdateRequest
import eu.monniot.feed.shared.util.epochSecondsToInstant
import eu.monniot.feed.shared.util.getRelativeTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class WebFeedRepository(private val feedApi: FeedApi) : FeedRepository {

    private val _items = MutableStateFlow<List<ArticleItem>>(emptyList())
    override val items: Flow<List<ArticleItem>> = _items

    override suspend fun refresh() {
        val articles = feedApi.getArticles(isRead = false).data
        val feedTitlesById = feedApi.getFeeds().data
            .associate { it.id to (it.custom_title ?: it.title) }
        _items.value = articles.map { article ->
            ArticleItem(
                id = article.id.toString(),
                title = article.title,
                description = article.content,
                pubDate = getRelativeTime(epochSecondsToInstant(article.published)),
                source = "Feed",
                url = article.link,
                feedTitle = feedTitlesById[article.feed_id],
            )
        }
    }

    override suspend fun markAsRead(articleId: Int) {
        feedApi.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
        _items.value = _items.value.filter { it.id != articleId.toString() }
    }

    override suspend fun getFeeds(): List<Feed> = feedApi.getFeeds().data

    override suspend fun addFeed(url: String): FeedAddResponse =
        feedApi.addFeed(FeedAddRequest(url)).data

    override suspend fun updateFeed(
        feedId: Int,
        customTitle: String?,
        fetchIntervalMinutes: Int,
        isPaused: Boolean,
    ) {
        feedApi.updateFeed(
            feedId,
            FeedUpdateRequest(
                custom_title = customTitle,
                fetch_interval_minutes = fetchIntervalMinutes,
                is_paused = isPaused,
            )
        )
    }

    override suspend fun deleteFeed(feedId: Int) {
        feedApi.deleteFeed(feedId)
    }
}
