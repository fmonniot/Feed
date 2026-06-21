package eu.monniot.feed.web.data

import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddRequest
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.FeedCategoryUpdateRequest
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.FeedUpdateRequest
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.RetentionRequest
import eu.monniot.feed.shared.util.epochSecondsToInstant
import eu.monniot.feed.shared.util.excerpt
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.shared.util.minutesToRead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class WebFeedRepository(private val feedApi: FeedApi) : FeedRepository {

    private val _items = MutableStateFlow<List<ArticleItem>>(emptyList())
    override val items: Flow<List<ArticleItem>> = _items

    override suspend fun refresh() {
        val articles = feedApi.getArticles().data
        val feedsById = feedApi.getFeeds().data.associateBy { it.id }
        _items.value = articles.map { article ->
            val feed = feedsById[article.feed_id]
            ArticleItem(
                id = article.id.toString(),
                title = article.title ?: "Untitled",
                description = article.content.orEmpty(),
                pubDate = article.published?.let { getRelativeTime(epochSecondsToInstant(it)) } ?: "",
                source = "Feed",
                url = article.link.orEmpty(),
                feedTitle = feed?.custom_title ?: feed?.title ?: feed?.url,
                feedId = article.feed_id,
                feedHue = feedHue(article.feed_id),
                isRead = article.is_read,
                author = article.author,
                minutesToRead = minutesToRead(article.content.orEmpty()),
                excerpt = excerpt(article.content.orEmpty()),
                linkStatus = article.link_status,
            )
        }
    }

    override suspend fun refreshUpstream(): RefreshResult = feedApi.refreshAllFeeds()

    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult =
        feedApi.refreshFeed(feedId)

    override suspend fun markAsRead(articleId: Int) {
        feedApi.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
        _items.value = _items.value.map {
            if (it.id == articleId.toString()) it.copy(isRead = true) else it
        }
    }

    override suspend fun markAsUnread(articleId: Int) {
        feedApi.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = false))
        _items.value = _items.value.map {
            if (it.id == articleId.toString()) it.copy(isRead = false) else it
        }
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

    override suspend fun getCategories(): List<Category> = feedApi.getCategories().data

    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {
        feedApi.setFeedCategory(feedId, FeedCategoryUpdateRequest(category_id = categoryId))
    }

    override suspend fun importOpml(opmlText: String): OpmlImportResult =
        feedApi.importOpml(opmlText).data

    override suspend fun getServerVersion(): String =
        feedApi.getVersion().version

    override suspend fun getParseError(feedId: Int): FeedParseError? =
        feedApi.getParseError(feedId)?.data

    override suspend fun clearArticles() {
        _items.value = emptyList()
    }

    override suspend fun getRetention(): Int? =
        feedApi.getRetention().days

    override suspend fun setRetention(days: Int?) {
        feedApi.setRetention(RetentionRequest(days = days))
    }
}
