package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
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
    // Per-article link health (e.g. last HEAD-probe HTTP status). Populated by the
    // per-platform repository impls (Android Room, web HTTP), never in shared code —
    // shared has no source for it, so it stays null on shared-constructed items.
    val linkStatus: Int? = null,
)

interface FeedRepository {
    val items: Flow<List<ArticleItem>>

    /**
     * Re-read the article list from our own server's DB (cheap; "action A" in
     * §5.3). This is what auto-poll and the post-upstream-fetch re-read use — it
     * is never a user-facing button on its own.
     */
    suspend fun refresh()

    /**
     * Trigger an immediate UPSTREAM fetch of all feeds via `POST /v1/feeds/refresh`
     * ("action B" in §5.3 — the primary "fetch now" gesture), WITHOUT re-reading
     * the list. Returns a typed result so the caller can tell success from a 429
     * rate-limit and fall back to a silent plain re-read. Callers are expected to
     * call [refresh] afterward to surface any new articles.
     */
    suspend fun refreshUpstream(): eu.monniot.feed.shared.api.RefreshResult

    /**
     * Trigger an immediate upstream fetch of a single feed via
     * `POST /v1/feeds/{id}/refresh` (the secondary, per-feed gesture). Same typed
     * result as [refreshUpstream].
     */
    suspend fun refreshFeedUpstream(feedId: Int): eu.monniot.feed.shared.api.RefreshResult
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
    /** Updates only the source URL of a feed via `PUT /v1/feeds/{id}`. */
    suspend fun updateFeedUrl(feedId: Int, newUrl: String)
    suspend fun deleteFeed(feedId: Int)
    suspend fun getCategories(): List<Category>
    suspend fun setFeedCategory(feedId: Int, categoryId: Int?)
    suspend fun importOpml(opmlText: String): OpmlImportResult
    suspend fun getServerVersion(): String
    suspend fun getParseError(feedId: Int): FeedParseError?
    suspend fun clearArticles()

    /** Returns the server-side retention in days, or null for "forever". */
    suspend fun getRetention(): Int?
    /** Sets the server-side retention in days (null = forever). */
    suspend fun setRetention(days: Int?)
}
