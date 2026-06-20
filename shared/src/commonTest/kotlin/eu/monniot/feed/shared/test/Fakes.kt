package eu.monniot.feed.shared.test

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.OpmlImportResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
) : FeedRepository {
    var refreshCallCount = 0
        private set
    var addFeedCallCount = 0
        private set

    override val items: Flow<List<ArticleItem>> = MutableStateFlow(emptyList())
    override suspend fun refresh() {
        refreshCallCount++
        refreshBehavior()
    }
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> = feedsToReturn
    override suspend fun addFeed(url: String): FeedAddResponse {
        addFeedCallCount++
        addFeedBehavior()
        return FeedAddResponse(id = 99, message = "ok")
    }
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
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

/** Builds a [Feed] fixture with sensible defaults; override fields as needed. */
fun makeFeed(
    id: Int,
    url: String,
    title: String? = "Feed $id",
    categoryId: Int? = null,
) = Feed(
    id = id,
    url = url,
    title = title,
    custom_title = null,
    is_paused = false,
    fetch_interval_minutes = 60,
    error_count = 0,
    last_fetched = null,
    unread_count = 0,
    category_id = categoryId,
)
