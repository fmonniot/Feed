package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.shared.api.Feed
import eu.monniot.feed.shared.api.FeedAddResponse
import eu.monniot.feed.shared.api.OpmlImportResult
import eu.monniot.feed.shared.api.ServerUrlStore
import eu.monniot.feed.shared.api.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Minimal in-memory Settings implementation for testing
// ---------------------------------------------------------------------------
private class TestSettings : com.russhwolf.settings.Settings {
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

// ---------------------------------------------------------------------------
// Fake repository
// ---------------------------------------------------------------------------
private class FakeRepository : FeedRepository {

    private val _items = MutableStateFlow<List<ArticleItem>>(emptyList())
    override val items: Flow<List<ArticleItem>> = _items

    private val _starred = MutableStateFlow<List<ArticleItem>>(emptyList())

    val toggledArticleIds = mutableListOf<Int>()

    fun seedStarred(items: List<ArticleItem>) {
        _starred.value = items
    }

    override suspend fun refresh() {}
    override suspend fun markAsRead(articleId: Int) {}
    override suspend fun getFeeds(): List<Feed> = emptyList()
    override suspend fun addFeed(url: String): FeedAddResponse = FeedAddResponse(id = 0, message = "")
    override suspend fun updateFeed(feedId: Int, customTitle: String?, fetchIntervalMinutes: Int, isPaused: Boolean) {}
    override suspend fun deleteFeed(feedId: Int) {}

    override suspend fun toggleStarred(articleId: Int) {
        toggledArticleIds += articleId
        _starred.value = _starred.value.map {
            if (it.id == articleId.toString()) it.copy(isStarred = !it.isStarred) else it
        }
    }

    override suspend fun getStarred(): Flow<List<ArticleItem>> = _starred

    override suspend fun getCategories(): List<Category> = listOf(
        Category(id = 1, name = "Tech", position = 0),
        Category(id = 2, name = "News", position = 1),
    )

    override suspend fun setFeedCategory(feedId: Int, categoryId: Int?) {}

    override suspend fun importOpml(opmlText: String): OpmlImportResult =
        OpmlImportResult(
            total_feeds = 0, imported = 0, already_exists = 0,
            failed = 0, categories_created = 0, feeds = emptyList(),
        )
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelStarredTest {

    /**
     * Create a FeedViewModel whose coroutines run in a child scope of [testScope].
     * The child scope can be cancelled safely without killing the test scope.
     */
    private fun makeViewModel(repo: FakeRepository, testScope: TestScope): FeedViewModel {
        val mockEngine = MockEngine { _ ->
            respond(content = "", status = HttpStatusCode.OK)
        }
        val client = HttpClient(mockEngine)
        val authApi = eu.monniot.feed.shared.api.AuthApi(client)
        val sessionManager = SessionManager()
        val settings = TestSettings()
        val serverUrlStore = ServerUrlStore(settings)
        val userPrefs = eu.monniot.feed.shared.data.UserPrefs(settings)
        // Create a child scope so vm.close() only cancels the child, not the test scope.
        val childScope = CoroutineScope(testScope.coroutineContext + Job())
        return FeedViewModel(
            repository = repo,
            authApi = authApi,
            sessionManager = sessionManager,
            clearCookies = {},
            serverUrlStore = serverUrlStore,
            userPrefs = userPrefs,
            coroutineScope = childScope,
        )
    }

    @Test
    fun starredItemsInitiallyEmpty() = runTest {
        val repo = FakeRepository()
        val vm = makeViewModel(repo, this)
        assertTrue(vm.starredItems.first().isEmpty(), "Expected empty starred list on init")
        vm.close()
    }

    @Test
    fun loadStarredPopulatesStarredItems() = runTest {
        val repo = FakeRepository()
        val article = ArticleItem(
            id = "1",
            title = "Test Article",
            description = "Content",
            pubDate = "2024-01-01",
            source = "Feed",
            url = "https://example.com",
            feedTitle = "Tech",
            isStarred = true,
        )
        repo.seedStarred(listOf(article))

        val vm = makeViewModel(repo, this)
        vm.loadStarred()
        advanceUntilIdle()

        val starred = vm.starredItems.value
        assertEquals(1, starred.size)
        assertEquals("1", starred[0].id)
        assertTrue(starred[0].isStarred)
        vm.close()
    }

    @Test
    fun toggleStarredDelegatesToRepository() = runTest {
        val repo = FakeRepository()
        val vm = makeViewModel(repo, this)
        vm.toggleStarred(5)
        advanceUntilIdle()
        assertTrue(repo.toggledArticleIds.contains(5), "Expected toggleStarred(5) to be called on repo")
        vm.close()
    }

    @Test
    fun loadCategoriesPopulatesCategoriesState() = runTest {
        val repo = FakeRepository()
        val vm = makeViewModel(repo, this)

        assertTrue(vm.categories.first().isEmpty(), "Categories should be empty initially")

        vm.loadCategories()
        advanceUntilIdle()

        val categories = vm.categories.value
        assertEquals(2, categories.size)
        assertEquals("Tech", categories[0].name)
        assertEquals("News", categories[1].name)
        vm.close()
    }

    @Test
    fun selectFeedUpdatesSelectedFeedId() = runTest {
        val repo = FakeRepository()
        val vm = makeViewModel(repo, this)
        assertFalse(vm.selectedFeedId.value != null)
        vm.selectFeed(42)
        assertEquals(42, vm.selectedFeedId.value)
        vm.selectFeed(null)
        assertEquals(null, vm.selectedFeedId.value)
        vm.close()
    }

    @Test
    fun selectArticleUpdatesSelectedArticleId() = runTest {
        val repo = FakeRepository()
        val vm = makeViewModel(repo, this)
        assertEquals(null, vm.selectedArticleId.value)
        vm.selectArticle("article-99")
        assertEquals("article-99", vm.selectedArticleId.value)
        vm.selectArticle(null)
        assertEquals(null, vm.selectedArticleId.value)
        vm.close()
    }
}
