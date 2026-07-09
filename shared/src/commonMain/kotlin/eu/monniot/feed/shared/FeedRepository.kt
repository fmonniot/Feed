package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.Article
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.sync.ArticleFilter
import eu.monniot.feed.shared.util.epochSecondsToInstant
import eu.monniot.feed.shared.util.excerpt
import eu.monniot.feed.shared.util.feedHue
import eu.monniot.feed.shared.util.getRelativeTime
import eu.monniot.feed.shared.util.minutesToRead
import kotlinx.coroutines.flow.Flow

data class ArticleItem(
    val id: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val url: String,
    val feedTitle: String?,
    val feedId: Int = 0,
    val feedHue: Int = 0,
    val isRead: Boolean = false,
    val author: String? = null,
    val minutesToRead: Int = 1,
    val excerpt: String = "",
    val linkStatus: Int? = null,
)

fun Article.toArticleItem(feedsById: Map<Int, Feed>): ArticleItem {
    val feed = feedsById[feed_id]
    return ArticleItem(
        id = id.toString(),
        title = title ?: "Untitled",
        description = content.orEmpty(),
        pubDate = published?.let { getRelativeTime(epochSecondsToInstant(it)) } ?: "",
        source = "Feed",
        url = link.orEmpty(),
        feedTitle = feed?.custom_title ?: feed?.title ?: feed?.url,
        feedId = feed_id,
        feedHue = feedHue(feed_id),
        isRead = is_read,
        author = author,
        minutesToRead = minutesToRead(content.orEmpty()),
        excerpt = excerpt(content.orEmpty()),
        linkStatus = link_status,
    )
}

interface FeedRepository {
    /**
     * Observe a windowed page of articles matching [filter], mapped to [ArticleItem].
     *
     * [window] is a zero-based [IntRange] (e.g. `0..49` for the first 50 rows).
     * Order is `published DESC, seq DESC`.
     *
     * **Window vs. badge contract:** The list is capped to [window].size rows and
     * contains whatever [filter] matches (read and unread articles for
     * [ArticleFilter.All]/[ArticleFilter.ByFeed]). The badge ([observeUnreadCount])
     * counts only unread articles globally. When all articles are unread,
     * `badge >= list.size`; when some are read, `badge` may be less than
     * `list.size`. The production UI uses a fixed window of
     * [FeedViewModel.DEFAULT_PAGE_SIZE] rows.
     */
    fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>>

    /**
     * Observe the count of unread articles matching [filter].
     *
     * This is a SQL `COUNT` — rows are never materialized. The count reflects
     * **all** matching unread articles, not just those visible in the current
     * [observePage] window.
     */
    fun observeUnreadCount(filter: ArticleFilter): Flow<Int>

    /**
     * Observe the total article count across all feeds, regardless of read state
     * or any active filter (BUG-43). Backs the "All articles" sidebar counter,
     * which must not change when switching filters or selecting a feed.
     */
    fun observeTotalCount(): Flow<Int>

    /**
     * Observe the total count of articles matching [filter], regardless of read
     * state, uncapped by [observePage]'s window. Backs the article-list header's
     * "N total" subtitle (scoped to the active view, unlike [observeTotalCount]).
     */
    fun observeCount(filter: ArticleFilter): Flow<Int>

    /**
     * Sync local mirror with the server via [SyncEngine] — a cheap re-read, no
     * upstream fan-out. This is the single re-read path: the reflexive gesture
     * ([eu.monniot.feed.shared.FeedViewModel.syncFromServer]), auto-poll, and
     * the upstream fan-out's own follow-up re-read
     * ([eu.monniot.feed.shared.FeedViewModel.fetchFromSources]) all call this.
     */
    suspend fun refresh()

    /**
     * Trigger an immediate UPSTREAM fetch of all feeds via `POST /v1/feeds/refresh`
     * ("action B"), WITHOUT syncing the local mirror. Returns a typed result so
     * the caller can tell success from a 429 rate-limit. Callers are expected to
     * call [refresh] afterward. #129: only called from the explicit "Force fetch
     * from sources" Settings action
     * ([eu.monniot.feed.shared.FeedViewModel.fetchFromSources]) — the reflexive
     * refresh gesture no longer triggers this.
     */
    suspend fun refreshUpstream(): eu.monniot.feed.shared.api.RefreshResult

    /**
     * Trigger an immediate upstream fetch of a single feed via
     * `POST /v1/feeds/{id}/refresh`. Same typed result as [refreshUpstream].
     */
    suspend fun refreshFeedUpstream(feedId: Int): eu.monniot.feed.shared.api.RefreshResult
    suspend fun markAsRead(articleId: Int)
    suspend fun markAsUnread(articleId: Int)

    /**
     * Mark every unread article in the local mirror as read (home-screen "mark
     * all as read"). Implemented as a fan-out over the store's unread ids through
     * [markArticlesAsRead], so it is optimistic and offline-capable like every
     * other read mutation. Scoped to what the client has mirrored locally —
     * articles not yet pulled to this device are not affected.
     */
    suspend fun markAllAsRead()

    /**
     * Mark every unread article in [feedId] as read. Fan-out over that feed's
     * unread ids through [markArticlesAsRead] — same offline-capable design as
     * [markAllAsRead].
     */
    suspend fun markFeedAsRead(feedId: Int)

    /**
     * Batch-mark a specific selection of articles as read (multi-select and the
     * bulk primitive that [markAllAsRead]/[markFeedAsRead] fan out to). Optimistic
     * and offline-capable: each id is enqueued + written to the local mirror
     * immediately (same pattern as [markAsRead]), then a single batched
     * `POST /v1/articles/read` call is made for the whole selection. An empty list
     * is a no-op (no network round trip).
     */
    suspend fun markArticlesAsRead(articleIds: List<Int>)

    /**
     * Batch-mark a specific selection of articles as unread — the undo twin of
     * [markArticlesAsRead], same optimistic/offline-capable idiom with a single
     * batched `POST /v1/articles/read` (`is_read=false`). An empty list is a no-op.
     */
    suspend fun markArticlesAsUnread(articleIds: List<Int>)
    suspend fun getFeeds(): List<Feed>
    suspend fun addFeed(url: String): FeedAddResponse
    suspend fun updateFeed(
        feedId: Int,
        customTitle: String?,
        fetchIntervalMinutes: Int,
        isPaused: Boolean,
    )
    suspend fun updateFeedUrl(feedId: Int, newUrl: String)
    suspend fun deleteFeed(feedId: Int)
    suspend fun getCategories(): List<Category>
    suspend fun setFeedCategory(feedId: Int, categoryId: Int?)
    suspend fun importOpml(opmlText: String): OpmlImportResult
    suspend fun getServerVersion(): String
    suspend fun getParseError(feedId: Int): FeedParseError?
    suspend fun clearArticles()

    suspend fun getRetention(): Int?
    suspend fun setRetention(days: Int?)
}
