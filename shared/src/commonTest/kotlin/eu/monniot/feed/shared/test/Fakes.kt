package eu.monniot.feed.shared.test

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.sync.ArticleFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Shared test doubles for the `FeedViewModel*Test` suites.
 *
 * Before this file each test re-declared its own ~25-line `Settings` impl and a
 * hand-rolled `FeedRepository` object. [InMemorySettings] and [FakeFeedRepository]
 * collapse that duplication; specialised behaviour (suspending gates, call
 * counting, throwing on every method) is expressed by passing constructor lambdas
 * or by subclassing [FakeFeedRepository] and overriding a single method.
 */

/** An in-memory [Settings] backed by a plain map — no platform persistence. */
class InMemorySettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun hasKey(key: String): Boolean = key in map
    override fun remove(key: String) { map.remove(key) }
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String) = map[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double) = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String) = map[key] as? Double
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float) = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String) = map[key] as? Float
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int) = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String) = map[key] as? Int
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long) = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String) = map[key] as? Long
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
}

/**
 * A configurable [FeedRepository] test double.
 *
 * Defaults: every method is a no-op (or returns an empty/placeholder value).
 * - [feedsToReturn] is what [getFeeds] yields.
 * - [categoriesToReturn] is what [getCategories] yields.
 * - [refreshBehavior] runs on every [refresh] call (default no-op). Use it to
 *   throw, count, or suspend on a gate. [refreshCallCount] tracks invocations.
 * - [addFeedBehavior] runs on every [addFeed] call before the success response;
 *   [addFeedCallCount] tracks invocations.
 *
 * Methods are `open` so a test needing one-off behaviour can subclass and
 * override a single method instead of re-implementing the whole interface.
 */
open class FakeFeedRepository(
    private val feedsToReturn: List<Feed> = emptyList(),
    private val categoriesToReturn: List<Category> = emptyList(),
    private val refreshBehavior: suspend () -> Unit = {},
    private val addFeedBehavior: suspend () -> Unit = {},
    /**
     * Runs on every [refreshUpstream] call and supplies its result. Default is a
     * no-op returning [RefreshResult.Success]. Use it to return a
     * [RefreshResult.RateLimited] or to throw, to exercise the §5.3 fallback.
     */
    private val refreshUpstreamBehavior: suspend () -> RefreshResult = { RefreshResult.Success(0) },
    /** Allows tests to provide a controllable article items for observePage. */
    val itemsFlow: MutableStateFlow<List<ArticleItem>> = MutableStateFlow(emptyList()),
) : FeedRepository {
    var refreshCallCount = 0
        private set
    var addFeedCallCount = 0
        private set
    /** Number of upstream-pull calls (action B) — distinct from [refreshCallCount] (action A). */
    var refreshUpstreamCallCount = 0
        private set
    /** feedId of the last [refreshFeedUpstream] call, or null if never called. */
    var lastRefreshFeedUpstreamId: Int? = null
        private set
    /** Number of [getFeeds] calls — verifies that the VM re-reads the feed list after mutations. */
    var getFeedsCallCount = 0
        private set

    /** Tracks the last filter passed to observePage for test assertions. */
    var lastObservePageFilter: ArticleFilter? = null
        private set

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> {
        lastObservePageFilter = filter
        return itemsFlow.map { items ->
            val filtered = when (filter) {
                is ArticleFilter.All -> items
                is ArticleFilter.UnreadOnly -> items.filter { !it.isRead }
                is ArticleFilter.ByFeed -> items.filter { it.feedId == filter.feedId }
            }
            filtered.take(window.last + 1)
        }
    }

    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> =
        itemsFlow.map { items ->
            when (filter) {
                is ArticleFilter.All -> items.count { !it.isRead }
                is ArticleFilter.UnreadOnly -> items.count { !it.isRead }
                is ArticleFilter.ByFeed -> items.count { it.feedId == filter.feedId && !it.isRead }
            }
        }

    override suspend fun refresh() {
        refreshCallCount++
        refreshBehavior()
    }

    override suspend fun refreshUpstream(): RefreshResult {
        refreshUpstreamCallCount++
        return refreshUpstreamBehavior()
    }

    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult {
        refreshUpstreamCallCount++
        lastRefreshFeedUpstreamId = feedId
        return refreshUpstreamBehavior()
    }
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> {
        getFeedsCallCount++
        return feedsToReturn
    }
    override suspend fun addFeed(url: String): FeedAddResponse {
        addFeedCallCount++
        addFeedBehavior()
        return FeedAddResponse(id = 99, message = "ok")
    }
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    var lastUpdateFeedUrlId: Int? = null
        private set
    var lastUpdateFeedUrlNewUrl: String? = null
        private set
    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {
        lastUpdateFeedUrlId = feedId
        lastUpdateFeedUrlNewUrl = newUrl
    }
    override suspend fun deleteFeed(feedId: Int) {}
    override suspend fun getCategories(): List<Category> = categoriesToReturn
    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}
    override suspend fun importOpml(opmlText: String): OpmlImportResult = OpmlImportResult(
        total_feeds = 0, imported = 0, already_exists = 0,
        failed = 0, categories_created = 0, feeds = emptyList(),
    )
    override suspend fun getServerVersion(): String = "0.0.0"
    override suspend fun getParseError(feedId: Int): FeedParseError? = null
    override suspend fun clearArticles() {}

    var retentionDays: Int? = 90
    override suspend fun getRetention(): Int? = retentionDays
    override suspend fun setRetention(days: Int?) { retentionDays = days }
}

/** Builds an [ArticleItem] fixture with sensible defaults; override fields as needed. */
fun makeArticle(
    id: String = "1",
    title: String = "Article $id",
    feedTitle: String = "Test Feed",
) = ArticleItem(
    id = id,
    title = title,
    description = "Description for $title",
    pubDate = "Mon, 1 Jan 2024",
    source = "test",
    url = "https://example.com/$id",
    feedTitle = feedTitle,
)

/** Builds a [Feed] fixture with sensible defaults; override fields as needed. */
fun makeFeed(
    id: Int,
    url: String,
    title: String? = "Feed $id",
    categoryId: Int? = null,
    severity: String? = null,
    lastErrorKind: String? = null,
    lastHttpStatus: Int? = null,
    consecutiveFailureCount: Int? = null,
    retriesPaused: Boolean? = null,
    nextRetryAt: Long? = null,
    feedStatus: String? = null,
    errorCount: Int = 0,
    first410At: Long? = null,
    lastFetched: Long? = null,
) = Feed(
    id = id,
    url = url,
    title = title,
    custom_title = null,
    is_paused = false,
    fetch_interval_minutes = 60,
    error_count = errorCount,
    last_fetched = lastFetched,
    unread_count = 0,
    category_id = categoryId,
    feed_status = feedStatus,
    first_410_at = first410At,
    severity = severity,
    last_error_kind = lastErrorKind,
    last_http_status = lastHttpStatus,
    consecutive_failure_count = consecutiveFailureCount,
    retries_paused = retriesPaused,
    next_retry_at = nextRetryAt,
)
