package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.ArticleReadUpdateRequest
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddRequest
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedApi
import eu.monniot.feed.shared.api.FeedCategoryUpdateRequest
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.FeedUpdateRequest
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.RetentionRequest
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.sync.ArticleStore
import eu.monniot.feed.shared.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class SharedFeedRepository(
    private val api: FeedApi,
    private val store: ArticleStore,
    private val syncEngine: SyncEngine,
) : FeedRepository {

    // Starts empty; first emission of observePage will have null feedTitles
    // until refresh() or getFeeds() populates it. This self-heals because the
    // combine re-emits when the cache updates.
    private val feedsCache = MutableStateFlow<Map<Int, Feed>>(emptyMap())

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> =
        store.observePage(filter, window).combine(feedsCache) { articles, feeds ->
            articles.map { it.toArticleItem(feeds) }
        }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
        store.observeUnreadCount(filter)

    override suspend fun refresh() {
        syncEngine.sync()
        refreshFeedsCache()
    }

    override suspend fun refreshUpstream(): RefreshResult = api.refreshAllFeeds()

    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult =
        api.refreshFeed(feedId)

    override suspend fun markAsRead(articleId: Int) {
        api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = true))
        store.markRead(articleId, isRead = true)
    }

    override suspend fun markAsUnread(articleId: Int) {
        api.markArticleRead(articleId, ArticleReadUpdateRequest(is_read = false))
        store.markRead(articleId, isRead = false)
    }

    override suspend fun getFeeds(): List<Feed> {
        val feeds = api.getFeeds().data
        feedsCache.value = feeds.associateBy { it.id }
        return feeds
    }

    override suspend fun addFeed(url: String): FeedAddResponse =
        api.addFeed(FeedAddRequest(url)).data

    override suspend fun updateFeed(
        feedId: Int,
        customTitle: String?,
        fetchIntervalMinutes: Int,
        isPaused: Boolean,
    ) {
        api.updateFeed(
            feedId,
            FeedUpdateRequest(
                custom_title = customTitle,
                fetch_interval_minutes = fetchIntervalMinutes,
                is_paused = isPaused,
            )
        )
    }

    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {
        api.updateFeed(feedId, FeedUpdateRequest(url = newUrl))
    }

    override suspend fun deleteFeed(feedId: Int) {
        api.deleteFeed(feedId)
        store.deleteByFeedId(feedId)
        feedsCache.update { it - feedId }
    }

    override suspend fun getCategories(): List<Category> = api.getCategories().data

    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {
        api.setFeedCategory(feedId, FeedCategoryUpdateRequest(category_id = categoryId))
    }

    override suspend fun importOpml(opmlText: String): OpmlImportResult =
        api.importOpml(opmlText).data

    override suspend fun getServerVersion(): String =
        api.getVersion().version

    override suspend fun getParseError(feedId: Int): FeedParseError? =
        api.getParseError(feedId)?.data

    override suspend fun clearArticles() {
        store.clear()
    }

    override suspend fun getRetention(): Int? =
        api.getRetention().days

    override suspend fun setRetention(days: Int?) {
        api.setRetention(RetentionRequest(days = days))
    }

    private suspend fun refreshFeedsCache() {
        try {
            feedsCache.value = api.getFeeds().data.associateBy { it.id }
        } catch (_: Exception) {
            // Best-effort; the cache may be stale but the sync itself succeeded.
        }
    }
}
