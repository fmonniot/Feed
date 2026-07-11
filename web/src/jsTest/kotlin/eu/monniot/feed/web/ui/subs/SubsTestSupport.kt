package eu.monniot.feed.web.ui.subs

import com.russhwolf.settings.Settings
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.FeedRepository
import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.AuthApi
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.FeedParseError
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.RefreshResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import eu.monniot.feed.shared.data.UserPrefs
import eu.monniot.feed.shared.sync.ArticleFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared test infrastructure for the #123 rail + pane integration tests —
 * a minimal in-memory [Settings] and a mutable, call-recording [FeedRepository]
 * fake so tests can assert exactly which shared category/feed actions
 * ([eu.monniot.feed.shared.FeedViewModel.createCategory] etc.) a UI gesture
 * (click, drag) ultimately triggers, without a live server.
 */
internal class SubsStubSettings : Settings {
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

/** In-memory, mutable, call-recording [FeedRepository] fake for the rail + pane tests. */
internal class SubsFakeFeedRepository(
    initialFeeds: List<Feed> = emptyList(),
    initialCategories: List<Category> = emptyList(),
) : FeedRepository {

    val feeds: MutableList<Feed> = initialFeeds.toMutableList()
    val categories: MutableList<Category> = initialCategories.toMutableList()
    private var nextFeedId = (feeds.maxOfOrNull { it.id } ?: 0) + 1
    private var nextCategoryId = (categories.maxOfOrNull { it.id } ?: 0) + 1

    val setFeedCategoryCalls = mutableListOf<Pair<Int, Int?>>()
    val createCategoryCalls = mutableListOf<String>()
    val renameCategoryCalls = mutableListOf<Pair<Int, String>>()
    val deleteCategoryCalls = mutableListOf<Pair<Int, Int?>>()
    val reorderCategoriesCalls = mutableListOf<List<Int>>()
    val addFeedCalls = mutableListOf<String>()
    val deleteFeedCalls = mutableListOf<Int>()

    override fun observePage(filter: ArticleFilter, window: IntRange): Flow<List<ArticleItem>> = flowOf(emptyList())
    override fun observeUnreadCount(filter: ArticleFilter): Flow<Int> = flowOf(0)
    override fun observeTotalCount(): Flow<Int> = flowOf(0)
    override fun observeCount(filter: ArticleFilter): Flow<Int> = flowOf(0)

    override suspend fun getFeeds(): List<Feed> = feeds.toList()

    override suspend fun refresh() {}
    override suspend fun refreshUpstream(): RefreshResult = RefreshResult.Success(0)
    override suspend fun refreshFeedUpstream(feedId: Int): RefreshResult = RefreshResult.Success(0)
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun markAsUnread(articleId: Int) {}
    override suspend fun markAllAsRead() {}
    override suspend fun markFeedAsRead(feedId: Int) {}
    override suspend fun markArticlesAsRead(articleIds: List<Int>) {}
    override suspend fun markArticlesAsUnread(articleIds: List<Int>) {}

    override suspend fun addFeed(url: String): FeedAddResponse {
        addFeedCalls += url
        val id = nextFeedId++
        feeds += Feed(
            id = id, url = url, title = "Feed $id", custom_title = null, is_paused = false,
            fetch_interval_minutes = 60, error_count = 0, last_fetched = null, unread_count = 0, category_id = null,
        )
        return FeedAddResponse(id = id, message = "ok")
    }

    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {
        val idx = feeds.indexOfFirst { it.id == feedId }
        if (idx >= 0) {
            feeds[idx] = feeds[idx].copy(
                custom_title = customTitle,
                fetch_interval_minutes = fetchIntervalMinutes,
                is_paused = isPaused,
            )
        }
    }

    override suspend fun updateFeedUrl(feedId: Int, newUrl: String) {
        val idx = feeds.indexOfFirst { it.id == feedId }
        if (idx >= 0) feeds[idx] = feeds[idx].copy(url = newUrl)
    }

    override suspend fun deleteFeed(feedId: Int) {
        deleteFeedCalls += feedId
        feeds.removeAll { it.id == feedId }
    }

    override suspend fun getCategories(): List<Category> = categories.sortedBy { it.position }

    override suspend fun createCategory(name: String): Int {
        createCategoryCalls += name
        val id = nextCategoryId++
        val pos = (categories.maxOfOrNull { it.position } ?: 0) + 1
        categories += Category(id = id, name = name, position = pos)
        return id
    }

    override suspend fun renameCategory(categoryId: Int, newName: String) {
        renameCategoryCalls += categoryId to newName
        val idx = categories.indexOfFirst { it.id == categoryId }
        if (idx >= 0) categories[idx] = categories[idx].copy(name = newName)
    }

    override suspend fun deleteCategory(categoryId: Int, reassignTo: Int?) {
        deleteCategoryCalls += categoryId to reassignTo
        for (i in feeds.indices) {
            if (feeds[i].category_id == categoryId) feeds[i] = feeds[i].copy(category_id = reassignTo)
        }
        categories.removeAll { it.id == categoryId }
    }

    override suspend fun reorderCategories(orderedCategoryIds: List<Int>) {
        reorderCategoriesCalls += orderedCategoryIds
        orderedCategoryIds.forEachIndexed { index, id ->
            val idx = categories.indexOfFirst { it.id == id }
            if (idx >= 0) categories[idx] = categories[idx].copy(position = index)
        }
    }

    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {
        setFeedCategoryCalls += feedId to categoryId
        val idx = feeds.indexOfFirst { it.id == feedId }
        if (idx >= 0) feeds[idx] = feeds[idx].copy(category_id = categoryId)
    }

    override suspend fun importOpml(opmlText: String): OpmlImportResult = OpmlImportResult(
        total_feeds = 0, imported = 0, already_exists = 0,
        failed = 0, categories_created = 0, feeds = emptyList(),
    )

    override suspend fun getServerVersion(): String = "0.0.0"
    override suspend fun getParseError(feedId: Int): FeedParseError? = null
    override suspend fun clearArticles() {}
    override suspend fun getRetention(): Int? = null
    override suspend fun setRetention(days: Int?) {}
}

internal fun subsMakeFeed(id: Int, name: String, categoryId: Int? = null, paused: Boolean = false, unread: Int = 0): Feed = Feed(
    id = id,
    url = "https://example.com/feed/$id",
    title = name,
    custom_title = null,
    is_paused = paused,
    fetch_interval_minutes = 60,
    error_count = 0,
    last_fetched = null,
    unread_count = unread,
    category_id = categoryId,
)

internal fun subsMakeViewModel(
    repo: SubsFakeFeedRepository,
    coroutineScope: CoroutineScope = CoroutineScope(Job()),
): FeedViewModel {
    val settings: Settings = SubsStubSettings()
    return FeedViewModel(
        repository = repo,
        authApi = AuthApi(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
        sessionManager = SessionManager(SubsStubSettings()),
        clearCookies = {},
        serverUrlStore = ServerUrlStore(settings),
        userPrefs = UserPrefs(settings),
        coroutineScope = coroutineScope,
    )
}
